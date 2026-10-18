package com.ysmef.geomodel.network;

import java.util.concurrent.TimeUnit;

/** Deadlines and bounded retry spacing for a stalled model transfer. */
final class TlmTransferTimeouts {
    static final long QUERY_NANOS = TimeUnit.SECONDS.toNanos(60);
    static final long CHECK_NANOS = TimeUnit.MINUTES.toNanos(2);
    // The server sends a zero-length chunk heartbeat while a download is queued.
    static final long FIRST_CHUNK_NANOS = TimeUnit.MINUTES.toNanos(5);
    static final long CHUNK_IDLE_NANOS = TimeUnit.MINUTES.toNanos(2);
    // Heartbeats keep queued transfers alive, but a broken server must not hold a slot forever.
    static final long MAX_DOWNLOAD_NANOS = TimeUnit.HOURS.toNanos(2);
    private static final long MAX_RETRY_NANOS = TimeUnit.MINUTES.toNanos(5);

    private TlmTransferTimeouts() {}

    static boolean expired(long now, long since, long lastChunk, long queryOrCheckLimit) {
        long last = lastChunk == 0 ? since : lastChunk;
        long limit = lastChunk == 0 ? queryOrCheckLimit : CHUNK_IDLE_NANOS;
        return now - last >= limit;
    }

    static boolean downloadExpired(long now, long since, long lastActivity) {
        return now - since >= MAX_DOWNLOAD_NANOS
                || expired(now, since, lastActivity, FIRST_CHUNK_NANOS);
    }

    static long retryDelay(int consecutiveTimeouts) {
        int doubling = Math.min(Math.max(0, consecutiveTimeouts - 1), 3);
        return Math.min(MAX_RETRY_NANOS, QUERY_NANOS << doubling);
    }
}
