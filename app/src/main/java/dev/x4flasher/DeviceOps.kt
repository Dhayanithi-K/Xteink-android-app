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

    private fun read(l: EspRomLoader, off: Int, len: Int): ByteArray {
        val o = ByteArrayOutputStream()
        l.readFlash(off, len, { o.write(it) }) { }
        return o.toByteArray()
    }

    /** Boots the stub, reads the partition table, otadata and both app headers. Read-only. */
    fun inspect(l: EspRomLoader, stubJson: String, log: (String) -> Unit): DeviceState {
        l.enterBootloader(); l.sync(); l.identify()
        log("Loading flasher stub…")
        l.loadStub(stubJson)

        val parts = PartitionTable.parse(read(l, 0x8000, 0x1000))
        if (parts.isEmpty()) throw EspError("No partition table found at 0x8000.")
        log("Partitions:")
        parts.forEach { log("  %-9s %d/0x%02X  @0x%X  size 0x%X".format(it.label, it.type, it.subtype, it.offset, it.size)) }

        val ota = parts.firstOrNull { it.type == 1 && it.subtype == 0 } ?: throw EspError("No otadata partition.")
        val slots = parts.filter { it.type == 0 && it.subtype in 0x10..0x1F }.sortedBy { it.subtype }
        if (slots.size < 2) throw EspError("Expected two OTA app slots, found ${slots.size}.")

        val ob = read(l, ota.offset, 0x2000)
        val entries = listOf(OtaEntry.of(ob, 0), OtaEntry.of(ob, 1))
        val best = entries.withIndex().filter { it.value.valid }.maxByOrNull { it.value.seq }
        val active = if (best == null) 0 else ((best.value.seq - 1) % slots.size).toInt()

        val valid = slots.map { ImageCheck.validate(read(l, it.offset, 0x1000)) == null }
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

    /** Full-flash dump (16 MB) via the stub. */
    fun backup(l: EspRomLoader, stubJson: String, out: OutputStream, log: (String) -> Unit, onProgress: (Float) -> Unit) {
        l.enterBootloader(); l.sync(); l.identify()
        log("Loading flasher stub…")
        l.loadStub(stubJson)
        log("Reading 16 MB. This takes a few minutes; keep the X4 connected.")
        l.readFlash(0, FLASH_TOTAL, { out.write(it) }, onProgress)
        out.flush()
        l.hardReset()
        log("Backup complete (MD5 checked).")
    }

    /** Point otadata at the other OTA slot. Reads with the stub, writes with the proven ROM path. */
    fun swapSlot(l: EspRomLoader, stubJson: String, log: (String) -> Unit) {
        val st = inspect(l, stubJson, log)
        val n = st.slots.size
        val target = (st.active + 1) % n
        if (!st.slotValid[target])
            throw EspError("${st.slots[target].label} has no valid firmware. Not switching.")

        var seq = st.maxSeq + 1
        while (((seq - 1) % n).toInt() != target) seq++
        val sectorIdx = if (st.maxSector == null) 0 else 1 - st.maxSector
        val off = st.otadata.offset + sectorIdx * 0x1000
        val sector = OtaData.buildSector(seq.toInt())

        log("Switching to ${st.slots[target].label} (otadata seq $seq)…")
        l.enterBootloader(); l.sync(); l.identify(); l.configureFlash()
        l.writeFlash(off, sector) { }
        if (!l.verifyMd5(off, sector)) throw EspError("otadata MD5 mismatch after write.")
        l.finish()
        l.hardReset()
        log("Done. Booting ${st.slots[target].label}.")
    }
}
