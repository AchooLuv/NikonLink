package com.achooluv.nikonlink.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun patchBumpIsNewer() {
        assertTrue(UpdateChecker.isNewer("0.1.23", "0.1.22"))
    }

    @Test
    fun sameVersionIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("0.1.22", "0.1.22"))
        assertFalse(UpdateChecker.isNewer("v0.1.22", "0.1.22"))
    }

    @Test
    fun olderVersionIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("0.1.9", "0.1.22"))
        assertFalse(UpdateChecker.isNewer("0.0.99", "0.1.0"))
    }

    @Test
    fun majorAndMinorBumpsWin() {
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.9.9"))
        assertTrue(UpdateChecker.isNewer("0.2.0", "0.1.99"))
    }

    @Test
    fun tagPrefixesAndSuffixesAreIgnored() {
        assertEquals(listOf(1, 2, 3), UpdateChecker.parse("v1.2.3"))
        assertEquals(listOf(0, 1, 23), UpdateChecker.parse("V0.1.23"))
        assertEquals(listOf(0, 2, 0), UpdateChecker.parse("0.2.0-beta1"))
        assertTrue(UpdateChecker.isNewer("v0.1.23", "0.1.22"))
    }

    @Test
    fun shorterVersionsCountMissingPartsAsZero() {
        assertFalse(UpdateChecker.isNewer("0.1", "0.1.0"))
        assertTrue(UpdateChecker.isNewer("0.1.1", "0.1"))
    }

    @Test
    fun notesAreShownAsPlainText() {
        val raw = "NikonLink v0.1.23\n\n**修复**\n- 用 `last_host` 的旧记录\n### 其他\n"
        val text = UpdateChecker.readableNotes(raw)
        assertFalse(text.contains("**"))
        assertFalse(text.contains("`"))
        assertFalse(text.contains("###"))
        assertTrue(text.contains("修复"))
        assertTrue(text.contains("旧记录"))
    }
}
