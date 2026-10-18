package com.ysmef.geomodel.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlmTransferTimeoutsTest {
    @Test
    void unansweredQueriesExpireAndRetrySpacingIsCapped() {
        long start = 1_000_000_000L;
        assertFalse(TlmTransferTimeouts.expired(start + TlmTransferTimeouts.QUERY_NANOS - 1,
                start, 0, TlmTransferTimeouts.QUERY_NANOS));
        assertTrue(TlmTransferTimeouts.expired(start + TlmTransferTimeouts.QUERY_NANOS,
                start, 0, TlmTransferTimeouts.QUERY_NANOS));
        assertEquals(TlmTransferTimeouts.QUERY_NANOS, TlmTransferTimeouts.retryDelay(1));
        assertEquals(TlmTransferTimeouts.QUERY_NANOS * 2, TlmTransferTimeouts.retryDelay(2));
        assertEquals(java.util.concurrent.TimeUnit.MINUTES.toNanos(5),
                TlmTransferTimeouts.retryDelay(20));
    }

    @Test
    void queueWaitHasLongerDeadlineButChunkProgressResetsIdleDeadline() {
        long start = 1_000_000_000L;
        long beforeFirst = start + TlmTransferTimeouts.FIRST_CHUNK_NANOS - 1;
        assertFalse(TlmTransferTimeouts.expired(beforeFirst, start, 0,
                TlmTransferTimeouts.FIRST_CHUNK_NANOS));
        assertTrue(TlmTransferTimeouts.expired(beforeFirst + 1, start, 0,
                TlmTransferTimeouts.FIRST_CHUNK_NANOS));

        long firstChunk = start + TlmTransferTimeouts.FIRST_CHUNK_NANOS / 2;
        assertFalse(TlmTransferTimeouts.expired(firstChunk + TlmTransferTimeouts.CHUNK_IDLE_NANOS - 1,
                start, firstChunk, TlmTransferTimeouts.FIRST_CHUNK_NANOS));
        assertTrue(TlmTransferTimeouts.expired(firstChunk + TlmTransferTimeouts.CHUNK_IDLE_NANOS,
                start, firstChunk, TlmTransferTimeouts.FIRST_CHUNK_NANOS));
    }

    @Test
    void heartbeatsCannotKeepABrokenTransferAliveForever() {
        long start = 1_000_000_000L;
        long lastHeartbeat = start + TlmTransferTimeouts.MAX_DOWNLOAD_NANOS
                - TlmTransferTimeouts.CHUNK_IDLE_NANOS / 2;
        assertFalse(TlmTransferTimeouts.downloadExpired(
                start + TlmTransferTimeouts.MAX_DOWNLOAD_NANOS - 1, start, lastHeartbeat));
        assertTrue(TlmTransferTimeouts.downloadExpired(
                start + TlmTransferTimeouts.MAX_DOWNLOAD_NANOS, start, lastHeartbeat));
    }
}
