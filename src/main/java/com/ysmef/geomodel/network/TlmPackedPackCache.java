package com.ysmef.geomodel.network;

import com.ysmef.geomodel.model.TlmPackArchive;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Reuses verified ZIP bytes across offers and downloads of the same source pack. */
final class TlmPackedPackCache {
    static final long VERIFY_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final int MAX_OFFERS = 256;

    record Offer(Path source, String hash, int size) {}

    /** The shared bytes are immutable after packing. Active downloads retain their own reference. */
    record Packed(Offer offer, byte[] bytes) {}

    private static final class Entry {
        final Offer offer;
        final long signature;
        final long byteLimit;
        final long verifiedAt;
        byte[] bytes;

        Entry(Packed packed, long signature, long byteLimit, long verifiedAt) {
            this.offer = packed.offer();
            this.signature = signature;
            this.byteLimit = byteLimit;
            this.verifiedAt = verifiedAt;
            this.bytes = packed.bytes();
        }
    }

    private static final class PackLock {
        int users;
    }

    private final LongSupplier clock;
    private final Map<Path, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Path, PackLock> packing = new HashMap<>();
    private long retainedBytes;

    TlmPackedPackCache(LongSupplier clock) {
        this.clock = clock;
    }

    Offer describe(Path source, long maxBytes) throws IOException {
        return resolve(source, maxBytes, false).offer();
    }

    Packed load(Path source, long maxBytes) throws IOException {
        return resolve(source, maxBytes, true);
    }

    private Packed resolve(Path path, long maxBytes, boolean needBytes) throws IOException {
        Path source = path.toAbsolutePath().normalize();
        PackLock lock = retainLock(source);
        try {
            // Query and download workers can pack different sources independently.
            synchronized (lock) {
                long signature = TlmPackArchive.signature(source);
                long now = clock.getAsLong();
                Packed cached = lookup(source, signature, maxBytes, now, needBytes);
                if (cached != null) return cached;

                byte[] bytes = TlmPackArchive.packForTransfer(source, maxBytes);
                String hash = TlmPackArchive.sha256(bytes);
                if (TlmPackArchive.signature(source) != signature) {
                    throw new IOException("TLM pack changed while being prepared");
                }
                Packed packed = new Packed(new Offer(source, hash, bytes.length), bytes);
                store(packed, signature, maxBytes, clock.getAsLong());
                return packed;
            }
        } finally {
            releaseLock(source, lock);
        }
    }

    private synchronized Packed lookup(Path source, long signature, long maxBytes, long now, boolean needBytes) {
        Entry entry = entries.get(source);
        if (entry == null) return null;
        if (entry.signature != signature || entry.byteLimit != maxBytes || now - entry.verifiedAt >= VERIFY_NANOS) {
            dropBytes(entry);
            entries.remove(source);
            return null;
        }
        if (needBytes && entry.bytes == null) return null;
        return new Packed(entry.offer, entry.bytes);
    }

    private synchronized void store(Packed packed, long signature, long maxBytes, long now) {
        Entry entry = new Entry(packed, signature, maxBytes, now);
        Entry previous = entries.put(packed.offer().source(), entry);
        if (previous != null) dropBytes(previous);
        retainedBytes += entry.bytes.length;

        Iterator<Entry> oldest = entries.values().iterator();
        while (entries.size() > MAX_OFFERS || retainedBytes > maxBytes) {
            dropBytes(oldest.next());
            if (entries.size() > MAX_OFFERS) oldest.remove();
        }
    }

    synchronized void expire(long now, long maxBytes) {
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.byteLimit != maxBytes || now - entry.verifiedAt >= VERIFY_NANOS) {
                dropBytes(entry);
                iterator.remove();
            }
        }
    }

    private void dropBytes(Entry entry) {
        if (entry.bytes != null) {
            retainedBytes -= entry.bytes.length;
            entry.bytes = null;
        }
    }

    private synchronized PackLock retainLock(Path source) {
        PackLock lock = packing.computeIfAbsent(source, ignored -> new PackLock());
        lock.users++;
        return lock;
    }

    private synchronized void releaseLock(Path source, PackLock lock) {
        if (--lock.users == 0) packing.remove(source);
    }
}
