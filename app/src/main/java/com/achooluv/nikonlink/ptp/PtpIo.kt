package com.achooluv.nikonlink.ptp

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Little-endian helpers shared by the PTP/IP framing code. */
internal object Le {
    fun putU16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    fun putU32(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    fun putU64(buf: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) {
            buf[off + i] = ((v ushr (8 * i)) and 0xFF).toByte()
        }
    }

    fun u16(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

    fun u32(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)

    fun u64(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) {
            v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        }
        return v
    }
}

/** Buffered little-endian reader over a PTP/IP socket stream. */
class LeInput(private val input: InputStream) {
    private val scratch = ByteArray(8)

    fun readFully(buf: ByteArray, off: Int, len: Int) {
        var read = 0
        while (read < len) {
            val n = input.read(buf, off + read, len - read)
            if (n < 0) throw EOFException("PTP/IP stream closed (wanted $len bytes, got $read)")
            read += n
        }
    }

    fun skip(len: Long) {
        var left = len
        while (left > 0) {
            val n = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (n < 0) throw EOFException("PTP/IP stream closed while skipping")
            left -= n
        }
    }

    /**
     * Streams exactly [length] bytes into [out] through a fixed buffer, reporting
     * cumulative progress. Heap usage stays at one buffer regardless of file size.
     * Callers transferring many chunks can pass a reusable [buffer] to avoid
     * allocating 256 KiB per chunk.
     */
    fun copyTo(
        out: java.io.OutputStream,
        length: Int,
        buffer: ByteArray = ByteArray(256 * 1024),
        onProgress: ((Long) -> Unit)? = null,
    ): Long {
        var left = length
        var total = 0L
        while (left > 0) {
            val n = minOf(left, buffer.size)
            readFully(buffer, 0, n)
            out.write(buffer, 0, n)
            left -= n
            total += n
            onProgress?.invoke(total)
        }
        return total
    }

    fun u16(): Int {
        readFully(scratch, 0, 2)
        return Le.u16(scratch, 0)
    }

    fun u32(): Int {
        readFully(scratch, 0, 4)
        return Le.u32(scratch, 0)
    }

    fun u64(): Long {
        readFully(scratch, 0, 8)
        return Le.u64(scratch, 0)
    }
}

/** Reads/writes the 8-byte length+type prefix shared by every PTP/IP packet. */
internal object Frame {
    const val MAX_PAYLOAD = 16 * 1024 * 1024

    fun writePacket(out: OutputStream, type: Int, payload: ByteArray) {
        val header = ByteArray(Ptp.HEADER_SIZE)
        Le.putU32(header, 0, Ptp.HEADER_SIZE + payload.size)
        Le.putU32(header, 4, type)
        out.write(header)
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    fun readHeader(input: LeInput): Pair<Int, Int> {
        val length = input.u32()
        val type = input.u32()
        if (length < Ptp.HEADER_SIZE) throw PtpLinkException("Invalid PTP/IP packet length $length")
        return type to (length - Ptp.HEADER_SIZE)
    }
}

/** Encodes a PTP/IP initiator name as UTF-16LE with a null terminator. */
internal fun encodeUtf16z(s: String): ByteArray {
    val bytes = ByteArray(s.length * 2 + 2)
    var i = 0
    for (ch in s) {
        bytes[i++] = (ch.code and 0xFF).toByte()
        bytes[i++] = ((ch.code ushr 8) and 0xFF).toByte()
    }
    bytes[i] = 0
    bytes[i + 1] = 0
    return bytes
}

/** Decodes a null-terminated UTF-16LE string at [off]; returns text and bytes used. */
internal fun decodeUtf16z(buf: ByteArray, off: Int): Pair<String, Int> {
    val sb = StringBuilder()
    var i = off
    while (i + 1 < buf.size) {
        val code = Le.u16(buf, i)
        i += 2
        if (code == 0) break
        sb.append(code.toChar())
    }
    return sb.toString() to (i - off)
}

/** Encodes a PTP string (u8 count incl. terminator, UTF-16LE units). */
internal fun encodePtpString(s: String): ByteArray {
    if (s.isEmpty()) return byteArrayOf(0)
    val units = s.take(254)
    val bytes = ByteArray(1 + (units.length + 1) * 2)
    bytes[0] = (units.length + 1).toByte()
    var i = 1
    for (ch in units) {
        bytes[i++] = (ch.code and 0xFF).toByte()
        bytes[i++] = ((ch.code ushr 8) and 0xFF).toByte()
    }
    bytes[i] = 0
    bytes[i + 1] = 0
    return bytes
}
