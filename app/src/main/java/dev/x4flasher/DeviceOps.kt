package dev.x4flasher

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Part(val label: String, val type: Int, val subtype: Int, val offset: Int, val size: Int)

object PartitionTable {
    /** Parses the ESP-IDF partition table sector (0x8000). Stops at the first non-entry. */
    fun parse(b: ByteArray): List<Part> {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val out = mutableListOf<Part>()
        var i = 0
        while (i + 32 <= b.size) {
            val m0 = b[i].toInt() and 0xFF
            val m1 = b[i + 1].toInt() and 0xFF
            if (m0 == 0xAA && m1 == 0x50) {
                out += Part(
                    String(b, i + 12, 16, Charsets.US_ASCII).substringBefore('\u0000'),
                    b[i + 2].toInt() and 0xFF, b[i + 3].toInt() and 0xFF,
                    bb.getInt(i + 4), bb.getInt(i + 8)
                )
            } else if (!(m0 == 0xEB && m1 == 0xEB)) break   // 0xEBEB = MD5 row, skip
            i += 32
        }
        return out
    }
}

class OtaEntry(val seq: Long, val valid: Boolean) { companion object }

/** ESP-IDF otadata: two 4 KB sectors, each holding {seq u32, label[20], state u32, crc u32}. */
object OtaData {
    private fun crc32le(init: Int, data: ByteArray): Int {   // same as esp_rom_crc32_le
        var c = init.inv()
        for (byte in data) {
            c = c xor (byte.toInt() and 0xFF)
            repeat(8) { c = if ((c and 1) != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        }
        return c.inv()
    }

    private fun crcFor(seq: Int): Int =
        crc32le(-1, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(seq).array())

    fun parse(sector: ByteArray): OtaEntry {
        val bb = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)
        val seq = bb.getInt(0)
        val valid = seq != -1 && bb.getInt(28) == crcFor(seq)
        return OtaEntry(seq.toLong() and 0xFFFFFFFFL, valid)
    }

    fun buildSector(seq: Int): ByteArray {
        val s = ByteArray(0x1000) { 0xFF.toByte() }
        val bb = ByteBuffer.wrap(s).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0, seq)
        bb.putInt(28, crcFor(seq))
        return s
    }
}

class DeviceState(
    val otadata: Part,
    val slots: List<Part>,
    val slotValid: List<Boolean>,
    val active: Int,
    val maxSeq: Long,
    val maxSector: Int?,
)

object DeviceOps {
    private const val FLASH_TOTAL = 0x1000000

    /** Reset into download mode and (re)load the stub, retrying on transient USB glitches. */
    private fun startStub(l: EspRomLoader, stub: String, log: (String) -> Unit) {
        var last: EspError? = null
        repeat(4) { i ->
            try {
                l.enterBootloader(); l.sync(); l.identify()
                log("Loading flasher stub…")
                l.loadStub(stub)
                return
            } catch (e: EspError) {
                last = e
                log("Retrying (${i + 1}/4): ${e.message}")
            }
        }
        throw last ?: EspError("Could not start the stub.")
    }

    /** Read a region; on a USB glitch, reset + reload the stub and read it again. */
    private fun read(l: EspRomLoader, stub: String, off: Int, len: Int, log: (String) -> Unit): ByteArray {
        var last: EspError? = null
        repeat(5) { i ->
            try {
                val o = ByteArrayOutputStream(len)
                l.readFlash(off, len, { o.write(it) }) { }
                return o.toByteArray()
            } catch (e: EspError) {
                last = e
                log("USB glitch at 0x%X: %s (retry %d/5)".format(off, e.message, i + 1))
                startStub(l, stub, log)
            }
        }
        throw last ?: EspError("Read failed.")
    }

    /** Boots the stub, reads the partition table, otadata and both app headers. Read-only. */
    fun inspect(l: EspRomLoader, stubJson: String, log: (String) -> Unit): DeviceState {
        startStub(l, stubJson, log)

        val parts = PartitionTable.parse(read(l, stubJson, 0x8000, 0x1000, log))
        if (parts.isEmpty()) throw EspError("No partition table found at 0x8000.")
        log("Partitions:")
        parts.forEach { log("  %-9s %d/0x%02X  @0x%X  size 0x%X".format(it.label, it.type, it.subtype, it.offset, it.size)) }

        val ota = parts.firstOrNull { it.type == 1 && it.subtype == 0 } ?: throw EspError("No otadata partition.")
        val slots = parts.filter { it.type == 0 && it.subtype in 0x10..0x1F }.sortedBy { it.subtype }
        if (slots.size < 2) throw EspError("Expected two OTA app slots, found ${slots.size}.")

        val ob = read(l, stubJson, ota.offset, 0x2000, log)
        val entries = listOf(OtaEntry.of(ob, 0), OtaEntry.of(ob, 1))
        val best = entries.withIndex().filter { it.value.valid }.maxByOrNull { it.value.seq }
        val active = if (best == null) 0 else ((best.value.seq - 1) % slots.size).toInt()

        val valid = slots.map { ImageCheck.validate(read(l, stubJson, it.offset, 0x1000, log)) == null }
        entries.forEachIndexed { i, e -> log("otadata[$i]: " + if (e.valid) "seq ${e.seq}" else "empty/invalid") }
        log("Booting slot: ${slots[active].label}" + if (best == null) " (default, otadata blank)" else "")
        slots.forEachIndexed { i, s -> log("${s.label}: " + if (valid[i]) "valid app image" else "no valid app image") }
        return DeviceState(ota, slots, valid, active, best?.value?.seq ?: 0L, best?.index)
    }

    private fun OtaEntry.Companion.of(b: ByteArray, i: Int) =
        OtaData.parse(b.copyOfRange(i * 0x1000, (i + 1) * 0x1000))

    /** Refuse to flash unless the layout is the standard X4 one our write path assumes. */
    fun requireStandardLayout(st: DeviceState, imageSize: Int) {
        val s0 = st.slots[0]
        if (s0.offset != ImageCheck.APP0_OFFSET || st.otadata.offset != ImageCheck.OTADATA_OFFSET ||
            st.otadata.size < ImageCheck.OTADATA_SIZE)
            throw EspError("Unexpected partition layout (app0 @0x%X, otadata @0x%X). Not flashing.".format(s0.offset, st.otadata.offset))
        if (s0.size < imageSize) throw EspError("Image is bigger than ${s0.label} (0x%X). Not flashing.".format(s0.size))
    }

    /** Full-flash dump (16 MB) via the stub, in MD5-checked pieces that shrink after a USB glitch. */
    fun backup(l: EspRomLoader, stubJson: String, out: OutputStream, log: (String) -> Unit, onProgress: (Float) -> Unit) {
        startStub(l, stubJson, log)
        log("Reading 16 MB. This takes a few minutes; keep the X4 connected.")
        var pos = 0
        var blocks = 16
        var glitches = 0
        while (pos < FLASH_TOTAL) {
            val len = minOf(blocks * 0x1000, FLASH_TOTAL - pos)
            try {
                val o = ByteArrayOutputStream(len)
                l.readFlash(pos, len, { o.write(it) }) { }
                out.write(o.toByteArray())
                pos += len
                onProgress(pos / FLASH_TOTAL.toFloat())
                blocks = minOf(16, blocks * 2)
            } catch (e: EspError) {
                glitches++
                if (glitches > 300) throw EspError("Too many USB errors (last: ${e.message}). Backup aborted.")
                if (glitches % 5 == 1) log("USB glitch #$glitches at 0x%X, recovering…".format(pos))
                blocks = maxOf(1, blocks / 2)
                startStub(l, stubJson, log)
            }
        }
        out.flush()
        l.hardReset()
        log("Backup complete (MD5 checked, $glitches USB glitches recovered).")
    }

    /** otadata sector that makes [target] the booting slot: (offset, sector bytes, seq). */
    private fun bootSector(st: DeviceState, target: Int): Triple<Int, ByteArray, Long> {
        val n = st.slots.size
        var seq = st.maxSeq + 1
        while (((seq - 1) % n).toInt() != target) seq++
        val sectorIdx = if (st.maxSector == null) 0 else 1 - st.maxSector
        return Triple(st.otadata.offset + sectorIdx * 0x1000, OtaData.buildSector(seq.toInt()), seq)
    }

    /** Flash into the slot that is NOT booting, verify, then switch boot to it. The old firmware stays put. */
    fun flashInactive(l: EspRomLoader, stubJson: String, image: ByteArray, log: (String) -> Unit, onProgress: (Float) -> Unit) {
        val st = inspect(l, stubJson, log)
        val target = (st.active + 1) % st.slots.size
        val slot = st.slots[target]
        if (image.size > slot.size) throw EspError("Image is bigger than ${slot.label} (0x%X). Not flashing.".format(slot.size))
        log("Flashing into ${slot.label} (0x%X). ${st.slots[st.active].label} stays as your fallback.".format(slot.offset))

        l.enterBootloader(); l.sync(); l.identify(); l.configureFlash()
        l.writeFlash(slot.offset, image, onProgress)
        log("Verifying MD5…")
        if (!l.verifyMd5(slot.offset, image)) throw EspError("MD5 mismatch. Boot slot unchanged; retry.")

        val (off, sector, seq) = bootSector(st, target)
        log("Verified. Switching boot to ${slot.label} (otadata seq $seq)…")
        l.writeFlash(off, sector) { }
        if (!l.verifyMd5(off, sector)) throw EspError("otadata MD5 mismatch after write.")
        l.finish()
        l.hardReset()
        log("Done. Booting ${slot.label}.")
    }

    /** Point otadata at the other OTA slot. Reads with the stub, writes with the proven ROM path. */
    fun swapSlot(l: EspRomLoader, stubJson: String, log: (String) -> Unit) {
        val st = inspect(l, stubJson, log)
        val target = (st.active + 1) % st.slots.size
        if (!st.slotValid[target])
            throw EspError("${st.slots[target].label} has no valid firmware. Not switching.")

        val (off, sector, seq) = bootSector(st, target)
        log("Switching to ${st.slots[target].label} (otadata seq $seq)…")
        l.enterBootloader(); l.sync(); l.identify(); l.configureFlash()
        l.writeFlash(off, sector) { }
        if (!l.verifyMd5(off, sector)) throw EspError("otadata MD5 mismatch after write.")
        l.finish()
        l.hardReset()
        log("Done. Booting ${st.slots[target].label}.")
    }
}
