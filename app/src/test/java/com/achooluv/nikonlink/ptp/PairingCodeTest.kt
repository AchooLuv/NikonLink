package com.achooluv.nikonlink.ptp

import org.junit.Assert.assertEquals
import org.junit.Test

/** Decoding of Nikon's GetPtpipPairingCode (0x952B) payload. */
class PairingCodeTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun decodesFourDigitCode() {
        val data = bytes(
            0x03, 0, 0, 0, 0x08, 0, 0, 0, 0, 0, 0, 0, 0x03, 0, 0, 0,
            0x04, 0, 0, 0, 0x02, 0x04, 0x04, 0x06,
        )
        assertEquals("2446", parsePairingCode(data))
    }

    @Test
    fun decodesAnotherSessionCode() {
        val data = bytes(
            0x04, 0, 0, 0, 0x08, 0, 0, 0, 0, 0, 0, 0, 0x04, 0, 0, 0,
            0x04, 0, 0, 0, 0x01, 0x04, 0x07, 0x02,
        )
        assertEquals("1472", parsePairingCode(data))
    }

    @Test
    fun rejectsShortPayload() {
        assertEquals("", parsePairingCode(bytes(0x04, 0, 0, 0)))
    }

    @Test
    fun rejectsNonDigitBytes() {
        val data = bytes(
            0x04, 0, 0, 0, 0x08, 0, 0, 0, 0, 0, 0, 0, 0x04, 0, 0, 0,
            0x04, 0, 0, 0, 0x0A, 0x04, 0x07, 0x02,
        )
        assertEquals("", parsePairingCode(data))
    }

    @Test
    fun rejectsZeroCount() {
        val data = bytes(
            0x04, 0, 0, 0, 0x08, 0, 0, 0, 0, 0, 0, 0, 0x04, 0, 0, 0,
            0x00, 0, 0, 0, 0x01, 0x04, 0x07, 0x02,
        )
        assertEquals("", parsePairingCode(data))
    }
}
