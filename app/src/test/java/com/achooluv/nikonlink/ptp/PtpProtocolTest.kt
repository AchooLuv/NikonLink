package com.achooluv.nikonlink.ptp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks down the PTP/IP framing and dataset parsing that the camera depends on. */
class PtpProtocolTest {

    @Test
    fun `little endian helpers round trip`() {
        val buf = ByteArray(8)
        Le.putU16(buf, 0, 0xABCD)
        Le.putU32(buf, 2, 0x12345678)
        assertEquals(0xABCD, Le.u16(buf, 0))
        assertEquals(0x12345678, Le.u32(buf, 2))

        val long = ByteArray(8)
        Le.putU64(long, 0, 0x0102030405060708L)
        assertEquals(0x0102030405060708L, Le.u64(long, 0))
    }

    @Test
    fun `frame writes length prefixed packet and reader parses it`() {
        val out = ByteArrayOutputStream()
        val payload = byteArrayOf(1, 2, 3, 4)
        Frame.writePacket(out, Ptp.PKT_OPERATION_RESPONSE, payload)

        val bytes = out.toByteArray()
        assertEquals(Ptp.HEADER_SIZE + payload.size, bytes.size)

        val input = LeInput(ByteArrayInputStream(bytes))
        val (type, length) = Frame.readHeader(input)
        assertEquals(Ptp.PKT_OPERATION_RESPONSE, type)
        assertEquals(payload.size, length)

        val read = ByteArray(length)
        input.readFully(read, 0, length)
        assertArrayEquals(payload, read)
    }

    @Test
    fun `init request encodes guid name and version`() {
        val out = ByteArrayOutputStream()
        val guid = ByteArray(16) { it.toByte() }
        val name = "NikonLink/0.1.1 (Android)"
        val nameBytes = encodeUtf16z(name)
        val payload = ByteArray(16 + nameBytes.size + 4)
        System.arraycopy(guid, 0, payload, 0, 16)
        System.arraycopy(nameBytes, 0, payload, 16, nameBytes.size)
        Le.putU32(payload, 16 + nameBytes.size, 0x00010000)
        Frame.writePacket(out, Ptp.PKT_INIT_COMMAND_REQUEST, payload)

        val input = LeInput(ByteArrayInputStream(out.toByteArray()))
        val (type, length) = Frame.readHeader(input)
        val body = ByteArray(length)
        input.readFully(body, 0, length)

        assertEquals(Ptp.PKT_INIT_COMMAND_REQUEST, type)
        assertArrayEquals(guid, body.copyOfRange(0, 16))
        val (decoded, consumed) = decodeUtf16z(body, 16)
        assertEquals(name, decoded)
        assertEquals(0x00010000, Le.u32(body, 16 + consumed))
    }

    @Test
    fun `uint32 array decodes handle lists`() {
        val payload = ByteArray(4 + 3 * 4)
        Le.putU32(payload, 0, 3)
        Le.putU32(payload, 4, 0x11)
        Le.putU32(payload, 8, 0x22)
        Le.putU32(payload, 12, 0x33)

        val handles = Datasets.uint32Array(payload)
        assertArrayEquals(intArrayOf(0x11, 0x22, 0x33), handles)
    }

    @Test(expected = PtpLinkException::class)
    fun `uint32 array rejects truncated payload`() {
        val payload = ByteArray(4)
        Le.putU32(payload, 0, 5)
        Datasets.uint32Array(payload)
    }

    @Test
    fun `object info decodes name size format and capture date`() {
        val bytes = objectInfoBytes(
            name = "DSC_0001.NEF",
            size = 24_500_000,
            format = Ptp.FORMAT_NEF,
            capture = "20261025T093012",
        )
        val info = Datasets.objectInfo(bytes)

        assertEquals("DSC_0001.NEF", info.filename)
        assertEquals(24_500_000L, info.compressedSize)
        assertEquals(Ptp.FORMAT_NEF, info.format)
        assertEquals(2026, info.captureTime?.let { millisToYear(it) })
    }

    @Test
    fun `ptp date time parses and rejects junk`() {
        assertEquals(2026, parsePtpDateTime("20261025T093012")?.let { millisToYear(it) })
        assertNull(parsePtpDateTime(""))
        assertNull(parsePtpDateTime("not a date"))
    }

    @Test
    fun `ptp string encodes length prefix and terminator`() {
        val encoded = encodePtpString("Z50II")
        assertEquals(6, encoded[0].toInt())
        assertEquals(1 + 6 * 2, encoded.size)
        val cursor = DataCursor(encoded)
        assertEquals("Z50II", cursor.string())
    }

    private fun millisToYear(millis: Long): Int {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = millis
        return cal.get(java.util.Calendar.YEAR)
    }

    private fun objectInfoBytes(name: String, size: Int, format: Int, capture: String): ByteArray {
        val out = ByteArrayOutputStream()

        fun u16(v: Int) {
            val b = ByteArray(2)
            Le.putU16(b, 0, v)
            out.write(b)
        }

        fun u32(v: Int) {
            val b = ByteArray(4)
            Le.putU32(b, 0, v)
            out.write(b)
        }

        u32(0x00010001) // storage id
        u16(format)
        u16(0) // protection
        u32(size)
        u16(Ptp.FORMAT_JPEG) // thumb format
        u32(160 * 120 * 3) // thumb size
        u32(160)
        u32(120)
        u32(5568)
        u32(3712)
        u32(14)
        u32(0)
        u16(0x0001) // association type
        u32(0)
        u32(1)
        out.write(encodePtpString(name))
        out.write(encodePtpString(capture))
        out.write(encodePtpString(capture))
        out.write(encodePtpString(""))
        return out.toByteArray()
    }
}
