package com.ysmef.geomodel.network;

import com.ysmef.geomodel.model.TlmPackArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TlmPackedPackCacheTest {
    private static final long LIMIT = 1_048_576;
    @TempDir Path temp;

    @Test
    void offerAndConcurrentDownloadsReuseTheVerifiedPack() throws Exception {
        Path source = createPack("shared");
        var cache = new TlmPackedPackCache(() -> 0L);
        var start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var offer = workers.submit(() -> {
                start.await();
                return cache.describe(source, LIMIT);
            });
            var download = workers.submit(() -> {
                start.await();
                return cache.load(source, LIMIT);
            });
            start.countDown();
            var first = download.get(5, TimeUnit.SECONDS);
            assertSame(offer.get(5, TimeUnit.SECONDS), first.offer());
            assertSame(first.bytes(), cache.load(source.resolve("."), LIMIT).bytes());
            assertEquals(TlmPackArchive.sha256(first.bytes()), first.offer().hash());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void sourceChangesAndLowerTransferLimitsInvalidateTheCachedPayload() throws IOException {
        Path source = createPack("edited");
        var cache = new TlmPackedPackCache(() -> 0L);
        var before = cache.describe(source, LIMIT);
        Files.writeString(geometry(source), "{\"changed\": true}");
        var after = cache.load(source, LIMIT);
        assertNotEquals(before.hash(), after.offer().hash());
        assertSame(after.offer(), cache.describe(source, LIMIT));
        assertThrows(IOException.class, () -> cache.load(source, 16));
    }

    @Test
    void fullHashRefreshDetectsAnEditThatPreservesTheMetadataSignature() throws IOException {
        Path source = createPack("same_metadata");
        Path geometry = geometry(source);
        FileTime fixedTime = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(geometry, fixedTime);
        long signature = TlmPackArchive.signature(source);
        var clock = new AtomicLong();
        var cache = new TlmPackedPackCache(clock::get);
        var before = cache.load(source, LIMIT);

        Files.writeString(geometry, "{\"version\":2}");
        Files.setLastModifiedTime(geometry, fixedTime);
        assertEquals(signature, TlmPackArchive.signature(source));
        clock.set(TlmPackedPackCache.VERIFY_NANOS - 1);
        assertSame(before.bytes(), cache.load(source, LIMIT).bytes());
        clock.incrementAndGet();
        cache.expire(clock.get(), LIMIT);
        assertNotEquals(before.offer().hash(), cache.load(source, LIMIT).offer().hash());
    }

    @Test
    void byteBudgetEvictsOlderPayloadWithoutInvalidatingTheOfferOrAnActiveDownload() throws IOException {
        Path firstPath = createPack("first");
        Path secondPath = createPack("second");
        long budget = Math.max(TlmPackArchive.packForTransfer(firstPath, LIMIT).length,
                TlmPackArchive.packForTransfer(secondPath, LIMIT).length);
        var cache = new TlmPackedPackCache(() -> 0L);
        var active = cache.load(firstPath, budget);
        cache.load(secondPath, budget);
        assertSame(active.offer(), cache.describe(firstPath, budget));

        var repacked = cache.load(firstPath, budget);
        assertNotSame(active.bytes(), repacked.bytes());
        assertArrayEquals(active.bytes(), repacked.bytes());
    }

    private Path createPack(String name) throws IOException {
        Path source = temp.resolve(name);
        Files.createDirectories(geometry(source).getParent());
        Files.writeString(source.resolve("assets/maid/maid_model.json"), """
                {"pack_name":"%s","model_list":[{"model_id":"maid:alice"}]}
                """.formatted(name));
        Files.writeString(geometry(source), "{\"version\":1}");
        return source;
    }

    private static Path geometry(Path source) {
        return source.resolve("assets/maid/models/entity/alice.json");
    }
}
