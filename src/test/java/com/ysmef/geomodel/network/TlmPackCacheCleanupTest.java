package com.ysmef.geomodel.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TlmPackCacheCleanupTest {
    @TempDir Path temp;

    @Test
    void removesOnlyInterruptedTransfersAndNeverRecleansActiveDownloads() throws IOException {
        Path root = Files.createDirectory(temp.resolve("server-cache"));
        Path interruptedDownload = Files.writeString(root.resolve(".download-old.part"), "partial");
        Path interruptedStaging = root.resolve(".staging-old/assets/maid/model.json");
        Files.createDirectories(interruptedStaging.getParent());
        Files.writeString(interruptedStaging, "partial");
        Path cachedZip = Files.writeString(root.resolve("verified.zip"), "cached");
        Path cachedPack = root.resolve("verified/assets/maid/model.json");
        Files.createDirectories(cachedPack.getParent());
        Files.writeString(cachedPack, "cached");
        Path unrelated = Files.writeString(root.resolve(".download-not-a-part.tmp"), "keep");
        Set<Path> cleanedRoots = new HashSet<>();

        assertEquals(2, TlmPackCacheCleanup.cleanOnce(root, cleanedRoots));
        assertFalse(Files.exists(interruptedDownload));
        assertFalse(Files.exists(root.resolve(".staging-old")));
        assertTrue(Files.exists(cachedZip));
        assertTrue(Files.exists(cachedPack));
        assertTrue(Files.exists(unrelated));

        Path activeDownload = Files.writeString(root.resolve(".download-active.part"), "in progress");
        assertEquals(0, TlmPackCacheCleanup.cleanOnce(root, cleanedRoots));
        assertTrue(Files.exists(activeDownload));
    }

    @Test
    void stagingSymlinkIsDeletedWithoutFollowingItsTarget() throws IOException {
        Path root = Files.createDirectory(temp.resolve("server-cache"));
        Path outside = temp.resolve("outside");
        Files.createDirectories(outside);
        Path keep = Files.writeString(outside.resolve("keep.txt"), "keep");
        try {
            Files.createSymbolicLink(root.resolve(".staging-linked"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "symbolic links unavailable: " + e);
        }

        assertEquals(1, TlmPackCacheCleanup.cleanOnce(root, new HashSet<>()));
        assertFalse(Files.exists(root.resolve(".staging-linked")));
        assertTrue(Files.exists(keep));
    }
}
