package com.achooluv.nikonlink

import org.junit.Assert.assertEquals
import org.junit.Test

/** Backoff schedule for re-establishing a dropped camera link. */
class ReconnectPolicyTest {

    @Test
    fun `first retries are quick then back off`() {
        assertEquals(1, reconnectDelaySeconds(1))
        assertEquals(2, reconnectDelaySeconds(2))
        assertEquals(4, reconnectDelaySeconds(3))
        assertEquals(8, reconnectDelaySeconds(4))
        assertEquals(16, reconnectDelaySeconds(5))
        assertEquals(30, reconnectDelaySeconds(6))
    }

    @Test
    fun `schedule caps at thirty seconds`() {
        assertEquals(30, reconnectDelaySeconds(7))
        assertEquals(30, reconnectDelaySeconds(30))
        assertEquals(30, reconnectDelaySeconds(999))
    }

    @Test
    fun `invalid attempts clamp to the first step`() {
        assertEquals(1, reconnectDelaySeconds(0))
        assertEquals(1, reconnectDelaySeconds(-5))
    }
}
