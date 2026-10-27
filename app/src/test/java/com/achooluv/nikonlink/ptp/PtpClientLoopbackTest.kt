package com.achooluv.nikonlink.ptp

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * End-to-end protocol test against a fake camera on real loopback sockets. This
 * covers the handshake, both TCP channels, transaction framing, dataset parsing
 * and chunked downloads without needing the actual body.
 */
class PtpClientLoopbackTest {

    private fun body(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

    private fun fixture(): FakeNikonServer {
        val jpeg = body(300_000)
        val nef = body(1_000_000)
        return FakeNikonServer(
            objects = mapOf(
                1 to FakeNikonServer.FakeObject(
                    name = "DSC_0001.JPG",
                    format = Ptp.FORMAT_JPEG,
                    body = jpeg,
                    thumb = ByteArray(200) { (it * 3).toByte() },
                ),
                2 to FakeNikonServer.FakeObject(
                    name = "DSC_0002.NEF",
                    format = Ptp.FORMAT_NEF,
                    body = nef,
                    thumb = ByteArray(64) { it.toByte() },
                ),
            ),
        )
    }

    @Test
    fun `connects, lists the card and streams an object in chunks`() {
        fixture().use { server ->
            val client = PtpIpClient("NikonLink-test/0.1.5")
            client.connect("127.0.0.1", server.port, connectTimeoutMs = 5_000)
            try {
                assertTrue("event channel should bind", client.eventsAvailable)

                val info = client.deviceInfo()
                assertTrue(info.model.contains("Z 50II"))
                assertTrue(info.supports(Ptp.OP_GET_PARTIAL_OBJECT))

                assertArrayEquals(intArrayOf(0x00010001), client.storageIds())
                assertArrayEquals(intArrayOf(1, 2), client.objectHandles(0x00010001))

                val objectInfo = client.objectInfo(1)
                assertEquals("DSC_0001.JPG", objectInfo.filename)
                assertEquals(300_000L, objectInfo.compressedSize)
                assertTrue(objectInfo.format == com.achooluv.nikonlink.ptp.Ptp.FORMAT_JPEG)

                val thumb = client.thumbnail(1)
                assertEquals(200, thumb.size)
                assertArrayEquals(ByteArray(200) { (it * 3).toByte() }, thumb)

                val sink = ByteArrayOutputStream()
                var offset = 0L
                val chunk = 128 * 1024
                while (offset < 300_000L) {
                    val written = client.downloadRange(1, offset, chunk) { input, count ->
                        input.copyTo(sink, count)
                    }
                    assertTrue("chunk should advance", written > 0)
                    offset += written
                }
                assertEquals(300_000, sink.size())
                assertArrayEquals(body(300_000), sink.toByteArray())
            } finally {
                client.close()
                server.close()
            }
        }
    }

    @Test
    fun `resume reads only from the requested offset`() {
        fixture().use { server ->
            val client = PtpIpClient("NikonLink-test/0.1.5")
            client.connect("127.0.0.1", server.port, connectTimeoutMs = 5_000)
            try {
                val start = 100_000L
                val sink = ByteArrayOutputStream()
                val written = client.downloadRange(1, start, 50_000) { input, count ->
                    input.copyTo(sink, count)
                }
                assertEquals(50_000L, written)
                assertArrayEquals(body(300_000).copyOfRange(start.toInt(), start.toInt() + 50_000), sink.toByteArray())
            } finally {
                client.close()
                server.close()
            }
        }
    }

    @Test
    fun `refused event channel still yields a usable session`() {
        FakeNikonServer(
            objects = mapOf(1 to FakeNikonServer.FakeObject("A.JPG", Ptp.FORMAT_JPEG, body(1000), body(10))),
            acceptEventChannel = false,
        ).use { server ->
            val client = PtpIpClient("NikonLink-test/0.1.5")
            client.connect("127.0.0.1", server.port, connectTimeoutMs = 5_000)
            try {
                assertFalse(client.eventsAvailable)
                assertArrayEquals(intArrayOf(1), client.objectHandles(0x00010001))
                val sink = ByteArrayOutputStream()
                client.downloadRange(1, 0, 1000) { input, count -> input.copyTo(sink, count) }
                assertEquals(1000, sink.size())
            } finally {
                client.close()
                server.close()
            }
        }
    }

    @Test
    fun `init failure surfaces as a link exception`() {
        FakeNikonServer(rejectInit = true).use { server ->
            val client = PtpIpClient("NikonLink-test/0.1.5")
            try {
                client.connect("127.0.0.1", server.port, connectTimeoutMs = 5_000)
                fail("expected PtpLinkException")
            } catch (expected: PtpLinkException) {
                assertTrue(expected.message.orEmpty().isNotEmpty())
            } finally {
                client.close()
                server.close()
            }
        }
    }
}
