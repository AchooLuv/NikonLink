package com.achooluv.nikonlink.transfer

import com.achooluv.nikonlink.camera.CameraFile
import com.achooluv.nikonlink.ptp.Ptp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RAW↔JPEG pairing and MediaStore name de-duplication. */
class PairingTest {

    private fun file(handle: Int, name: String, format: Int) =
        CameraFile(handle = handle, name = name, format = format, size = 1_000, captureTime = null)

    @Test
    fun `raw pulls in the jpeg sibling`() {
        val files = listOf(
            file(1, "DSC_0001.NEF", Ptp.FORMAT_NEF),
            file(2, "DSC_0001.JPG", Ptp.FORMAT_JPEG),
            file(3, "DSC_0002.NEF", Ptp.FORMAT_NEF),
        )
        val expanded = expandRawJpegPairs(files)
        assertEquals(setOf(1, 2, 3), expanded.map { it.handle }.toSet())
        assertEquals(3, expanded.size)
    }

    @Test
    fun `raw without a sibling keeps the selection as is`() {
        val files = listOf(file(1, "DSC_0009.NEF", Ptp.FORMAT_NEF))
        assertEquals(listOf(1), expandRawJpegPairs(files).map { it.handle })
    }

    @Test
    fun `already selected jpeg is not duplicated`() {
        val files = listOf(
            file(1, "DSC_0001.NEF", Ptp.FORMAT_NEF),
            file(2, "DSC_0001.JPG", Ptp.FORMAT_JPEG),
        )
        val expanded = expandRawJpegPairs(files)
        assertEquals(2, expanded.size)
        assertEquals(listOf(1, 2), expanded.map { it.handle })
    }

    @Test
    fun `jpeg only selections are untouched`() {
        val files = listOf(file(1, "A.JPG", Ptp.FORMAT_JPEG), file(2, "B.JPG", Ptp.FORMAT_JPEG))
        assertEquals(listOf(1, 2), expandRawJpegPairs(files).map { it.handle })
    }

    @Test
    fun `existing name gets an index suffix`() {
        val taken = setOf("DSC_0001.JPG")
        assertEquals("DSC_0001 (1).JPG", uniqueDisplayName("DSC_0001.JPG") { it in taken })
    }

    @Test
    fun `free name is kept verbatim`() {
        assertEquals("DSC_0042.NEF", uniqueDisplayName("DSC_0042.NEF") { false })
    }

    @Test
    fun `name without extension still de-duplicates`() {
        val taken = setOf("VIDEO")
        assertEquals("VIDEO (1)", uniqueDisplayName("VIDEO") { it in taken })
    }

    @Test
    fun `exhausted suffixes fall back to a timestamped name`() {
        val result = uniqueDisplayName("DSC_0001.JPG") { true }
        assertTrue(result.startsWith("DSC_0001-"))
        assertTrue(result.endsWith(".JPG"))
    }
}
