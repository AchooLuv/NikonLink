package com.achooluv.nikonlink.exif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hand-built TIFF/EXIF payloads: no camera needed to prove the parser. */
class ExifReaderTest {

    private class LeWriter {
        val out = java.io.ByteArrayOutputStream()
        fun u8(v: Int) { out.write(v and 0xFF) }
        fun u16(v: Int) { u8(v); u8(v ushr 8) }
        fun u32(v: Int) { u8(v); u8(v ushr 8); u8(v ushr 16); u8(v ushr 24) }
        fun ascii(s: String) { s.forEach { u8(it.code) } }
        fun bytes(): ByteArray = out.toByteArray()
    }

    /** IFD entry: tag, type, count, then either the inline value or an offset. */
    private fun entry(w: LeWriter, tag: Int, type: Int, count: Int, inline: Int) {
        w.u16(tag); w.u16(type); w.u32(count); w.u32(inline)
    }

    private fun exifFixture(shutterCount: Int?): ByteArray {
        // Layout: TIFF header (8) | IFD0 (2 + 12 + 4) | Exif IFD (2 + 4*12 + 4) | data
        val ifd0Offset = 8
        val ifd0Size = 2 + 12 + 4
        val exifIfdOffset = ifd0Offset + ifd0Size
        val exifIfdSize = 2 + 4 * 12 + 4
        val dataStart = exifIfdOffset + exifIfdSize

        val lens = "NIKKOR Z 24-70mm f/4 S\u0000"
        val lensOffset = dataStart
        val fNumberOffset = lensOffset + lens.length
        val focalOffset = fNumberOffset + 8
        val makerOffset = focalOffset + 8
        val makerIfdSize = if (shutterCount != null) 2 + 12 + 4 else 2 + 4
        val makerSize = 10 + makerIfdSize
        val total = makerOffset + makerSize

        val w = LeWriter()
        // TIFF header, little endian
        w.u8('I'.code); w.u8('I'.code); w.u16(42); w.u32(ifd0Offset)
        // IFD0: one entry pointing at the Exif IFD
        w.u16(1); entry(w, 0x8769, 4, 1, exifIfdOffset); w.u32(0)
        // Exif IFD: LensModel, FNumber, FocalLength, MakerNote
        w.u16(4)
        entry(w, 0xA434, 2, lens.length, lensOffset)
        entry(w, 0x829D, 5, 1, fNumberOffset)
        entry(w, 0x920A, 5, 1, focalOffset)
        entry(w, 0x927C, 7, makerSize, makerOffset)
        w.u32(0)
        // payloads
        w.ascii(lens)
        w.u32(4); w.u32(1)          // f/4
        w.u32(700); w.u32(10)       // 70mm
        // Nikon MakerNote: "Nikon\0" + version(2) + padding(2) = 10 bytes, then IFD
        w.ascii("Nikon"); w.u8(0); w.u8(2); w.u8(0); w.u8(0); w.u8(0)
        if (shutterCount != null) {
            w.u16(1); entry(w, 0xA7, 4, 1, shutterCount); w.u32(0)
        } else {
            w.u16(0); w.u32(0)
        }

        val bytes = w.bytes()
        check(bytes.size == total) { "fixture size ${bytes.size} != $total" }
        return bytes
    }

    @Test
    fun `fixture layout is self consistent`() {
        val bytes = exifFixture(1)
        // Little-endian TIFF magic and a sane IFD0 pointer.
        assertEquals('I'.code.toByte(), bytes[0])
        assertEquals(8, bytes[4].toInt() and 0xFF)
    }

    @Test
    fun `reads lens exposure and shutter count from a raw tiff`() {
        val summary = ExifReader.read(exifFixture(12345))
        assertEquals("NIKKOR Z 24-70mm f/4 S", summary.lensModel)
        assertEquals("f/4", summary.aperture)
        assertEquals("70mm", summary.focalLength)
        assertEquals(12345, summary.shutterCount)
        assertTrue(summary.hasAnything)
    }

    @Test
    fun `reads the same payload wrapped in a jpeg app1 segment`() {
        val tiff = exifFixture(777)
        val jpeg = LeWriter()
        jpeg.u8(0xFF); jpeg.u8(0xD8)
        jpeg.u8(0xFF); jpeg.u8(0xE1)
        // JPEG segment lengths are big-endian, unlike the TIFF payload.
        val segmentLength = 2 + 6 + tiff.size
        jpeg.u8((segmentLength ushr 8) and 0xFF); jpeg.u8(segmentLength and 0xFF)
        jpeg.ascii("Exif"); jpeg.u8(0); jpeg.u8(0)
        tiff.forEach { jpeg.u8(it.toInt()) }

        val summary = ExifReader.read(jpeg.bytes())
        assertEquals(777, summary.shutterCount)
        assertEquals("NIKKOR Z 24-70mm f/4 S", summary.lensModel)
    }

    @Test
    fun `missing maker note leaves shutter count unknown`() {
        val summary = ExifReader.read(exifFixture(null))
        assertNull(summary.shutterCount)
        assertEquals("f/4", summary.aperture)
    }

    @Test
    fun `garbage input degrades to an empty summary`() {
        val summary = ExifReader.read(ByteArray(64) { 0x7F })
        assertNull(summary.lensModel)
        assertNull(summary.shutterCount)
        assertTrue(!summary.hasAnything)
    }

    @Test
    fun `empty input does not throw`() {
        val summary = ExifReader.read(ByteArray(0))
        assertTrue(!summary.hasAnything)
    }
}
