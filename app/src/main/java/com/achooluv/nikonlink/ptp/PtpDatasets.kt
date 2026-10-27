package com.achooluv.nikonlink.ptp

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Cursor over a PTP dataset payload (little-endian, bounds checked). */
internal class DataCursor(private val buf: ByteArray) {
    var offset = 0
        private set

    val remaining: Int get() = buf.size - offset

    private fun need(n: Int) {
        if (remaining < n) throw PtpLinkException("Truncated PTP dataset at $offset (need $n, have $remaining)")
    }

    fun u8(): Int {
        need(1)
        return buf[offset++].toInt() and 0xFF
    }

    fun u16(): Int {
        need(2)
        val v = Le.u16(buf, offset)
        offset += 2
        return v
    }

    fun u32(): Int {
        need(4)
        val v = Le.u32(buf, offset)
        offset += 4
        return v
    }

    fun u64(): Long {
        need(8)
        val v = Le.u64(buf, offset)
        offset += 8
        return v
    }

    /** PTP string: u8 count (including terminator) then that many UTF-16LE units. */
    fun string(): String {
        val count = u8()
        if (count == 0) return ""
        val bytes = count * 2
        need(bytes)
        val sb = StringBuilder(count)
        var i = 0
        while (i < bytes) {
            val code = Le.u16(buf, offset + i)
            i += 2
            if (code != 0) sb.append(code.toChar())
        }
        offset += bytes
        return sb.toString()
    }

    fun u16Array(): IntArray {
        val count = u32()
        if (count < 0 || count > remaining / 2) throw PtpLinkException("Bad uint16 array size $count")
        val out = IntArray(count)
        for (i in 0 until count) out[i] = u16()
        return out
    }
}

/** Metadata for one object (photo, video or folder) on the camera. */
data class ObjectInfo(
    val storageId: Int,
    val format: Int,
    val compressedSize: Long,
    val thumbFormat: Int,
    val thumbSize: Long,
    val thumbWidth: Int,
    val thumbHeight: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val parentObject: Int,
    val filename: String,
    val captureDateRaw: String,
) {
    /** Capture instant in epoch millis, or null when the camera reports nothing usable. */
    val captureTime: Long?
        get() = parsePtpDateTime(captureDateRaw)

    val isFolder: Boolean get() = format == Ptp.FORMAT_ASSOCIATION
}

/** Camera identity from the DeviceInfo dataset. */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String,
    val operations: Set<Int>,
) {
    fun supports(op: Int): Boolean = op in operations
}

/** One storage card in the camera. */
data class StorageInfo(
    val storageId: Int,
    val description: String,
    val volumeLabel: String,
    val maxCapacity: Long,
    val freeSpace: Long,
)

internal object Datasets {
    fun objectInfo(buf: ByteArray): ObjectInfo {
        val c = DataCursor(buf)
        val storageId = c.u32()
        val format = c.u16()
        c.u16() // protection status
        val size = c.u32().toLong() and 0xFFFFFFFFL
        val thumbFormat = c.u16()
        val thumbSize = c.u32().toLong() and 0xFFFFFFFFL
        val thumbW = c.u32()
        val thumbH = c.u32()
        val imageW = c.u32()
        val imageH = c.u32()
        c.u32() // bit depth
        val parent = c.u32()
        c.u16() // association type
        c.u32() // association description
        c.u32() // sequence number
        val name = c.string()
        val capture = c.string()
        c.string() // modification date
        return ObjectInfo(
            storageId = storageId,
            format = format,
            compressedSize = size,
            thumbFormat = thumbFormat,
            thumbSize = thumbSize,
            thumbWidth = thumbW,
            thumbHeight = thumbH,
            imageWidth = imageW,
            imageHeight = imageH,
            parentObject = parent,
            filename = name,
            captureDateRaw = capture,
        )
    }

    fun deviceInfo(buf: ByteArray): DeviceInfo {
        val c = DataCursor(buf)
        c.u16() // standard version
        c.u32() // vendor extension id
        c.u16() // vendor extension version
        c.string() // vendor extension description
        c.u16() // functional mode
        val ops = c.u16Array().toSet()
        c.u16Array() // events
        c.u16Array() // device props
        c.u16Array() // capture formats
        c.u16Array() // image formats
        val manufacturer = c.string()
        val model = c.string()
        val version = c.string()
        val serial = c.string()
        return DeviceInfo(manufacturer, model, version, serial, ops)
    }

    fun storageInfo(storageId: Int, buf: ByteArray): StorageInfo {
        val c = DataCursor(buf)
        c.u16() // storage type
        c.u16() // filesystem type
        c.u16() // access capability
        val capacity = c.u64()
        val free = c.u64()
        c.u32() // free space in objects
        val description = c.string()
        val label = c.string()
        return StorageInfo(
            storageId = storageId,
            description = description,
            volumeLabel = label,
            maxCapacity = if (capacity < 0) 0 else capacity,
            freeSpace = if (free < 0) 0 else free,
        )
    }

    fun uint32Array(buf: ByteArray): IntArray {
        val c = DataCursor(buf)
        val count = c.u32()
        if (count < 0 || count.toLong() * 4 > c.remaining) {
            throw PtpLinkException("Bad uint32 array size $count")
        }
        val out = IntArray(count)
        for (i in 0 until count) out[i] = c.u32()
        return out
    }
}

private val PTP_DATE = Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})""")

/** Parses "YYYYMMDDThhmmss" as local time. */
internal fun parsePtpDateTime(raw: String): Long? {
    val m = PTP_DATE.find(raw) ?: return null
    val (y, mo, d, h, mi, s) = m.destructured
    return runCatching {
        val cal = Calendar.getInstance(TimeZone.getDefault(), Locale.US)
        cal.clear()
        cal.set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), s.toInt())
        cal.timeInMillis
    }.getOrNull()
}
