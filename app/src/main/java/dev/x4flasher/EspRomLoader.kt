package dev.x4flasher

import android.util.Base64
import com.hoho.android.usbserial.driver.UsbSerialPort
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class EspError(msg: String) : Exception(msg)

/** Guards so we only ever write a sane ESP32-C3 *app* image into app0. */
object ImageCheck {
    const val APP0_OFFSET = 0x10000
    const val APP0_MAX = 0x640000
    const val OTADATA_OFFSET = 0xE000
    const val OTADATA_SIZE = 0x2000

    /** Returns an error message, or null if the image looks like an ESP32-C3 app. */
    fun validate(img: ByteArray): String? {
        if (img.size < 64) return "File is too small to be firmware."
        if (img.size > APP0_MAX) return "Bigger than the app slot (looks like a full-flash image). Not supported."
        if ((img[0].toInt() and 0xFF) != 0xE9) return "Not an ESP image (bad magic byte)."
        val chip = (img[12].toInt() and 0xFF) or ((img[13].toInt() and 0xFF) shl 8)
        if (chip != 5) return "Not built for ESP32-C3 (chip id $chip)."
        val desc = ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN).getInt(32)
        if (desc != 0xABCD5432.toInt()) return "No app descriptor found: this looks like a bootloader/partition image, not an app."
        return null
    }
}

/**
 * Minimal ESP ROM-bootloader client (no stub): sync, flash write, MD5 verify.
 * The ROM loader cannot read flash back; that needs the stub (future work).
 */
class EspRomLoader(private val port: UsbSerialPort, private val log: (String) -> Unit) {

    private companion object {
        const val CMD_FLASH_BEGIN = 0x02
        const val CMD_MEM_BEGIN = 0x05
        const val CMD_MEM_END = 0x06
        const val CMD_MEM_DATA = 0x07
        const val CMD_READ_FLASH = 0xD2
        const val CMD_FLASH_DATA = 0x03
        const val CMD_FLASH_END = 0x04
        const val CMD_SYNC = 0x08
        const val CMD_READ_REG = 0x0A
        const val CMD_SPI_SET_PARAMS = 0x0B
        const val CMD_SPI_ATTACH = 0x0D
        const val CMD_SPI_FLASH_MD5 = 0x13

        const val BLOCK = 0x400
        const val FLASH_TOTAL = 0x1000000
        const val CHIP_MAGIC_ADDR = 0x40001000
        val C3_MAGIC = setOf(0x6921506FL, 0x1B31506FL, 0x4881506FL, 0x4361506FL)
    }

    private class Resp(val value: Int, val data: ByteArray)

    // ---- SLIP framing -------------------------------------------------------
    private val frames = ArrayDeque<ByteArray>()
    private val cur = ByteArrayOutputStream()
    private var inFrame = false
    private var esc = false
    private val rx = ByteArray(4096)

    private fun pump(timeoutMs: Int) {
        val n = port.read(rx, timeoutMs)
        for (i in 0 until n) {
            val b = rx[i].toInt() and 0xFF
            if (b == 0xC0) {
                if (inFrame && cur.size() > 0) frames.addLast(cur.toByteArray())
                cur.reset(); inFrame = true; esc = false
            } else if (inFrame) {
                if (esc) {
                    cur.write(if (b == 0xDC) 0xC0 else if (b == 0xDD) 0xDB else b); esc = false
                } else if (b == 0xDB) esc = true
                else cur.write(b)
            }
        }
    }

    private fun nextFrame(deadline: Long): ByteArray? {
        while (true) {
            frames.removeFirstOrNull()?.let { return it }
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            pump(minOf(left, 100L).toInt())
        }
    }

    private fun slip(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xC0)
        for (b in payload) {
            when (b.toInt() and 0xFF) {
                0xC0 -> { out.write(0xDB); out.write(0xDC) }
                0xDB -> { out.write(0xDB); out.write(0xDD) }
                else -> out.write(b.toInt() and 0xFF)
            }
        }
        out.write(0xC0)
        return out.toByteArray()
    }

    private fun send(cmd: Int, data: ByteArray, chk: Int) {
        val pkt = ByteBuffer.allocate(8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        pkt.put(0.toByte()).put(cmd.toByte()).putShort(data.size.toShort()).putInt(chk).put(data)
        port.write(slip(pkt.array()), 5000)
    }

    private fun command(cmd: Int, data: ByteArray = ByteArray(0), chk: Int = 0, timeoutMs: Long = 3000): Resp {
        send(cmd, data, chk)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val f = nextFrame(deadline)
                ?: throw EspError("Timeout waiting for reply to command 0x%02X".format(cmd))
            if (f.size < 8 || f[0].toInt() != 1 || (f[1].toInt() and 0xFF) != cmd) continue
            val bb = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
            val size = bb.getShort(2).toInt() and 0xFFFF
            return Resp(bb.getInt(4), f.copyOfRange(8, minOf(f.size, 8 + size)))
        }
    }

    private fun reason(c: Int) = when (c) {
        5 -> "invalid message"; 6 -> "failed to act on command"; 7 -> "invalid CRC"
        8 -> "flash write error"; 9 -> "flash read error"; 10 -> "flash read length error"
        else -> "error 0x%02X".format(c)
    }

    /** For commands whose reply body is only status bytes: first byte != 0 means failure. */
    private fun expectOk(cmd: Int, data: ByteArray = ByteArray(0), chk: Int = 0, timeoutMs: Long = 3000) {
        val r = command(cmd, data, chk, timeoutMs)
        if (r.data.size < 2) throw EspError("Short reply to command 0x%02X".format(cmd))
        if (r.data[0].toInt() != 0)
            throw EspError("Command 0x%02X failed: %s".format(cmd, reason(r.data[1].toInt() and 0xFF)))
    }

    private fun le(vararg v: Int): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { bb.putInt(it) }
        return bb.array()
    }

    private fun padTo4(d: ByteArray): ByteArray {
        val n = (d.size + 3) / 4 * 4
        return if (n == d.size) d else d.copyOf(n).also { for (i in d.size until n) it[i] = 0xFF.toByte() }
    }

    // ---- Public steps -------------------------------------------------------

    /** Same DTR/RTS dance esptool uses for the ESP32-C3's built-in USB Serial/JTAG. */
    fun enterBootloader() {
        log("Resetting into download mode…")
        port.setRTS(false); port.setDTR(false)
        Thread.sleep(100)
        port.setDTR(true); port.setRTS(false)
        Thread.sleep(200)
        port.setRTS(true); port.setDTR(false); port.setRTS(true)
        Thread.sleep(200)
        port.setRTS(false); port.setDTR(false)
        val end = System.currentTimeMillis() + 400   // swallow boot-log noise
        while (System.currentTimeMillis() < end) pump(50)
        frames.clear()
    }

    fun sync() {
        val payload = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }
        repeat(12) {
            try {
                command(CMD_SYNC, payload, 0, 400)
                val end = System.currentTimeMillis() + 150
                while (nextFrame(end) != null) { /* drain extra sync replies */ }
                log("Bootloader answered.")
                return
            } catch (_: EspError) { }
        }
        throw EspError("No answer from the ROM bootloader. Wake the X4, use a data cable, retry.")
    }

    fun identify() {
        val magic = command(CMD_READ_REG, le(CHIP_MAGIC_ADDR)).value.toLong() and 0xFFFFFFFFL
        if (magic !in C3_MAGIC) throw EspError("Not an ESP32-C3 (magic 0x%X). Aborting.".format(magic))
        log("ESP32-C3 confirmed.")
    }

    fun configureFlash() {
        expectOk(CMD_SPI_ATTACH, ByteArray(8))
        expectOk(CMD_SPI_SET_PARAMS, le(0, FLASH_TOTAL, 0x10000, 0x1000, 0x100, 0xFFFF))
    }

    fun writeFlash(offset: Int, data: ByteArray, onProgress: (Float) -> Unit) {
        val img = padTo4(data)
        val blocks = (img.size + BLOCK - 1) / BLOCK
        val eraseSize = blocks * BLOCK
        val eraseTimeout = maxOf(3000L, 30_000L * eraseSize / 1_000_000L)
        expectOk(CMD_FLASH_BEGIN, le(eraseSize, blocks, BLOCK, offset, 0), 0, eraseTimeout)
        for (seq in 0 until blocks) {
            val block = ByteArray(BLOCK) { 0xFF.toByte() }
            System.arraycopy(img, seq * BLOCK, block, 0, minOf(BLOCK, img.size - seq * BLOCK))
            var x = 0xEF
            for (b in block) x = x xor (b.toInt() and 0xFF)
            expectOk(CMD_FLASH_DATA, le(BLOCK, seq, 0, 0) + block, x, 5000)
            if (seq % 16 == 0) onProgress(seq / blocks.toFloat())
        }
        onProgress(1f)
    }

    fun verifyMd5(offset: Int, data: ByteArray): Boolean {
        val img = padTo4(data)
        val r = command(CMD_SPI_FLASH_MD5, le(offset, img.size, 0, 0), 0, maxOf(3000L, 8000L * img.size / 1_000_000L))
        if (r.data.size < 34 || r.data[32 + 0].toInt() != 0) throw EspError("MD5 command failed.")
        val remote = String(r.data, 0, 32, Charsets.US_ASCII)
        val local = MessageDigest.getInstance("MD5").digest(img).joinToString("") { "%02x".format(it) }
        return remote.equals(local, ignoreCase = true)
    }

    /** ROM has no erase command, so write 0xFF over otadata. Blank otadata => bootloader picks app0. */
    fun blankOtadata() {
        writeFlash(ImageCheck.OTADATA_OFFSET, ByteArray(ImageCheck.OTADATA_SIZE) { 0xFF.toByte() }) { }
    }

    fun finish() {
        try { expectOk(CMD_FLASH_END, le(1)) } catch (_: EspError) { }
    }

    // ---- Stub loader (RAM) : used only for reading flash ---------------------

    private fun ramUpload(data: ByteArray, addr: Int) {
        val block = 0x1800
        val n = (data.size + block - 1) / block
        expectOk(CMD_MEM_BEGIN, le(data.size, n, block, addr))
        for (seq in 0 until n) {
            val chunk = data.copyOfRange(seq * block, minOf(data.size, (seq + 1) * block))
            var x = 0xEF
            for (b in chunk) x = x xor (b.toInt() and 0xFF)
            expectOk(CMD_MEM_DATA, le(chunk.size, seq, 0, 0) + chunk, x)
        }
    }

    /** Upload and start esptool's flasher stub (JSON with base64 text/data). */
    fun loadStub(json: String) {
        val o = JSONObject(json)
        val entry = o.getLong("entry").toInt()
        ramUpload(Base64.decode(o.getString("text"), Base64.DEFAULT), o.getLong("text_start").toInt())
        if (o.has("data")) {
            val d = Base64.decode(o.getString("data"), Base64.DEFAULT)
            if (d.isNotEmpty()) ramUpload(d, o.getLong("data_start").toInt())
        }
        expectOk(CMD_MEM_END, le(0, entry))
        val f = nextFrame(System.currentTimeMillis() + 3000)
        if (f == null || String(f, Charsets.US_ASCII) != "OHAI") throw EspError("Stub did not start (no OHAI).")
        log("Stub running.")
    }

    /** Stub-only. Streams flash contents to [sink] in blocks, verifies the stub's MD5 of the stream. */
    fun readFlash(offset: Int, length: Int, sink: (ByteArray) -> Unit, onProgress: (Float) -> Unit) {
        expectOk(CMD_READ_FLASH, le(offset, length, 0x1000, 64), 0, 5000)
        val md = MessageDigest.getInstance("MD5")
        var got = 0
        while (got < length) {
            val f = nextFrame(System.currentTimeMillis() + 10_000)
                ?: throw EspError("Timeout reading flash at 0x%X".format(offset + got))
            sink(f); md.update(f); got += f.size
            port.write(slip(le(got)), 5000)
            if ((got / 0x1000) % 16 == 0) onProgress(got / length.toFloat())
        }
        onProgress(1f)
        val digest = nextFrame(System.currentTimeMillis() + 10_000) ?: throw EspError("No MD5 from stub.")
        if (!md.digest().contentEquals(digest)) throw EspError("Read MD5 mismatch. Try again.")
    }

    fun hardReset() {
        port.setDTR(false); port.setRTS(true)
        Thread.sleep(100)
        port.setRTS(false)
    }
}
