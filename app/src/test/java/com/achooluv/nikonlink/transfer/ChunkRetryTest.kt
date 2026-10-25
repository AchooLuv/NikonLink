package com.achooluv.nikonlink.transfer

import com.achooluv.nikonlink.ptp.Ptp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The chunk-downgrade ladder used when a body rejects large GetPartialObject reads. */
class ChunkRetryTest {

    @Test
    fun `rejected chunk halves`() {
        assertEquals(2 * 1024 * 1024, nextChunkSize(4 * 1024 * 1024, Ptp.RESP_INVALID_PARAMETER))
        assertEquals(1024 * 1024, nextChunkSize(2 * 1024 * 1024, Ptp.RESP_INCOMPLETE_TRANSFER))
    }

    @Test
    fun `downgrade never goes below the floor`() {
        assertEquals(MIN_CHUNK_SIZE, nextChunkSize(1024 * 1024, Ptp.RESP_INVALID_PARAMETER))
        assertEquals(MIN_CHUNK_SIZE, nextChunkSize(MIN_CHUNK_SIZE, Ptp.RESP_INVALID_PARAMETER))
    }

    @Test
    fun `unrelated failures keep the chunk size`() {
        assertEquals(4 * 1024 * 1024, nextChunkSize(4 * 1024 * 1024, Ptp.RESP_ACCESS_DENIED))
        assertEquals(4 * 1024 * 1024, nextChunkSize(4 * 1024 * 1024, Ptp.RESP_DEVICE_BUSY))
        assertEquals(4 * 1024 * 1024, nextChunkSize(4 * 1024 * 1024, Ptp.RESP_OK))
    }
}
