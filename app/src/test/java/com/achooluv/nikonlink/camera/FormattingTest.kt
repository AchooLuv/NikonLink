package com.achooluv.nikonlink.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/** Human-readable sizes and transfer estimates used by the UI. */
class FormattingTest {

    @Test
    fun `bytes scale through units`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("2 KB", formatBytes(2 * 1024L))
        assertEquals("1.5 MB", formatBytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("2.00 GB", formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `eta formats seconds minutes and hours`() {
        val mb = 1024L * 1024
        assertEquals("10s", formatEta(10 * mb, mb))
        assertEquals("1m 30s", formatEta(90 * mb, mb))
        assertEquals("2h 0m", formatEta(7200 * mb, mb))
    }

    @Test
    fun `eta is empty without a usable rate`() {
        assertEquals("", formatEta(100, 0))
        assertEquals("", formatEta(0, 1000))
    }

    @Test
    fun `speed is a dash before the first sample`() {
        assertEquals("—", formatSpeed(0))
        assertEquals("1.0 MB/s", formatSpeed(1024L * 1024))
    }
}
