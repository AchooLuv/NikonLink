package com.achooluv.nikonlink.exif

/** What we can learn from a JPEG/NEF header without downloading the whole file. */
data class ExifSummary(
    val lensModel: String? = null,
    val focalLength: String? = null,
    val aperture: String? = null,
    val shutterCount: Int? = null,
) {
    val hasAnything: Boolean
        get() = lensModel != null || focalLength != null || aperture != null || shutterCount != null
}

/**
 * Minimal, dependency-free EXIF reader: TIFF header → IFD0 → Exif IFD → Nikon
 * MakerNote. Only the few fields the connect screen shows are decoded, and any
 * malformed input degrades to "nothing found" instead of throwing.
 */
object ExifReader {

    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_MAKER_NOTE = 0x927C
    private const val TAG_FNUMBER = 0x829D
    private const val TAG_FOCAL_LENGTH = 0x920A
    private const val TAG_LENS_MODEL = 0xA434
    private const val TAG_SHUTTER_COUNT = 0xA7

    fun read(bytes: ByteArray): ExifSummary {
        val tiff = findTiff(bytes) ?: return ExifSummary()
        return runCatching { parseTiff(tiff) }.getOrDefault(ExifSummary())
    }

    /** Locates the TIFF block inside a JPEG (APP1) or a TIFF-based NEF. */
    private fun findTiff(bytes: ByteArray): ByteArray? {
        if (bytes.size > 8 && isTiff(bytes, 0)) return bytes
        var i = 2
        while (i + 4 <= bytes.size) {
            if ((bytes[i].toInt() and 0xFF) != 0xFF) {
                i++
                continue
            }
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xE1) {
                val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
                val payloadStart = i + 4
                if (length >= 8 && payloadStart + length - 2 <= bytes.size) {
                    if (bytes[payloadStart] == 'E'.code.toByte() &&
                        bytes[payloadStart + 1] == 'x'.code.toByte() &&
                        bytes[payloadStart + 2] == 'i'.code.toByte() &&
                        bytes[payloadStart + 3] == 'f'.code.toByte()
                    ) {
                        return bytes.copyOfRange(payloadStart + 6, payloadStart + length - 2)
                    }
                }
            }
            if (marker == 0xDA) break // start of scan: no more metadata
            i += 2
        }
        return null
    }

    private fun isTiff(bytes: ByteArray, at: Int): Boolean {
        if (at + 4 > bytes.size) return false
        val a = bytes[at]
        val b = bytes[at + 1]
        val c = bytes[at + 2]
        val d = bytes[at + 3]
        return (a == 'I'.code.toByte() && b == 'I'.code.toByte() && c == 0x2A.toByte() && d == 0.toByte()) ||
            (a == 'M'.code.toByte() && b == 'M'.code.toByte() && c == 0.toByte() && d == 0x2A.toByte())
    }

    private class Tiff(val data: ByteArray, forcedLittle: Boolean? = null) {
        val little: Boolean = forcedLittle ?: (data[0] == 'I'.code.toByte())

        fun u16(offset: Int): Int {
            if (offset + 2 > data.size) throw IndexOutOfBoundsException()
            return if (little) {
                (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
            } else {
                ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            }
        }

        fun u32(offset: Int): Int {
            if (offset + 4 > data.size) throw IndexOutOfBoundsException()
            return if (little) {
                (data[offset].toInt() and 0xFF) or
                    ((data[offset + 1].toInt() and 0xFF) shl 8) or
                    ((data[offset + 2].toInt() and 0xFF) shl 16) or
                    ((data[offset + 3].toInt() and 0xFF) shl 24)
            } else {
                ((data[offset].toInt() and 0xFF) shl 24) or
                    ((data[offset + 1].toInt() and 0xFF) shl 16) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    (data[offset + 3].toInt() and 0xFF)
            }
        }

        fun rational(offset: Int): Double? {
            val num = u32(offset)
            val den = u32(offset + 4)
            if (den == 0) return null
            return num.toDouble() / den.toDouble()
        }
    }

    private class Entry(val tag: Int, val type: Int, val count: Int, val valueOffset: Int)

    private fun entries(tiff: Tiff, ifdOffset: Int): List<Entry> {
        val count = tiff.u16(ifdOffset)
        val list = ArrayList<Entry>(count.coerceAtMost(64))
        for (index in 0 until count) {
            val base = ifdOffset + 2 + index * 12
            if (base + 12 > tiff.data.size) break
            list += Entry(
                tag = tiff.u16(base),
                type = tiff.u16(base + 2),
                count = tiff.u32(base + 4),
                valueOffset = base + 8,
            )
        }
        return list
    }

    private fun typeSize(type: Int): Int = when (type) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9 -> 4
        5, 10 -> 8
        else -> 1
    }

    private fun dataOffset(tiff: Tiff, entry: Entry): Int {
        val size = typeSize(entry.type) * entry.count
        return if (size <= 4) entry.valueOffset else tiff.u32(entry.valueOffset)
    }

    private fun ascii(tiff: Tiff, entry: Entry): String? {
        val start = dataOffset(tiff, entry)
        if (start < 0 || start >= tiff.data.size) return null
        val end = minOf(tiff.data.size, start + entry.count)
        val text = String(tiff.data, start, end - start, Charsets.US_ASCII).trim('\u0000', ' ', '\n', '\r')
        return text.ifBlank { null }
    }

    private fun parseTiff(bytes: ByteArray): ExifSummary {
        val tiff = Tiff(bytes)
        var lens: String? = null
        var focal: String? = null
        var aperture: String? = null
        var shutter: Int? = null

        val ifd0 = tiff.u32(4)
        val exifIfd = entries(tiff, ifd0).firstOrNull { it.tag == TAG_EXIF_IFD }
            ?.let { tiff.u32(it.valueOffset) }

        val exifEntries = exifIfd?.let { entries(tiff, it) }.orEmpty()
        exifEntries.firstOrNull { it.tag == TAG_LENS_MODEL }?.let { lens = ascii(tiff, it) }
        exifEntries.firstOrNull { it.tag == TAG_FOCAL_LENGTH }?.let { entry ->
            val start = dataOffset(tiff, entry)
            tiff.rational(start)?.let { focal = "${it.toInt()}mm" }
        }
        exifEntries.firstOrNull { it.tag == TAG_FNUMBER }?.let { entry ->
            val start = dataOffset(tiff, entry)
            tiff.rational(start)?.let { aperture = "f/${formatOneDecimal(it)}" }
        }
        exifEntries.firstOrNull { it.tag == TAG_MAKER_NOTE }?.let { entry ->
            val start = dataOffset(tiff, entry)
            shutter = readShutterCount(tiff.data, start, entry.count, tiff.little)
        }

        return ExifSummary(lens, focal, aperture, shutter)
    }

    private fun formatOneDecimal(value: Double): String {
        val rounded = Math.round(value * 10.0) / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }

    /** Reads tag 0x00A7 (ShutterCount) from a Nikon MakerNote. */
    private fun readShutterCount(data: ByteArray, start: Int, length: Int, little: Boolean): Int? {
        if (start <= 0 || start >= data.size || length <= 8) return null
        val end = minOf(data.size, start + length)
        val note = data.copyOfRange(start, end)

        // A MakerNote has no TIFF header of its own: it inherits the containing
        // file's byte order. (Reading it as big-endian silently returns nothing.)
        val inlineTiff = if (isTiff(note, 0)) Tiff(note) else Tiff(note, little)

        val ifdCandidates = mutableListOf<Int>()
        if (note.size > 11 &&
            note[0] == 'N'.code.toByte() && note[1] == 'i'.code.toByte() &&
            note[2] == 'k'.code.toByte() && note[3] == 'o'.code.toByte() &&
            note[4] == 'n'.code.toByte()
        ) {
            // "Nikon\0" + 2-byte version + 2-byte padding → IFD starts at byte 10.
            ifdCandidates += 10
        }
        if (isTiff(note, 0)) ifdCandidates += Tiff(note).u32(4)
        ifdCandidates += 0

        for (offset in ifdCandidates) {
            if (offset <= 0 || offset >= note.size) continue
            val found = runCatching {
                entries(inlineTiff, offset).firstOrNull { it.tag == TAG_SHUTTER_COUNT }?.let { entry ->
                    when (entry.type) {
                        3 -> inlineTiff.u16(entry.valueOffset)
                        4 -> inlineTiff.u32(entry.valueOffset)
                        else -> null
                    }
                }
            }.getOrNull()
            if (found != null && found > 0) return found
        }
        return null
    }
}
