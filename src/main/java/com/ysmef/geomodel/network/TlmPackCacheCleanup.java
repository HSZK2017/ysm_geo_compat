package com.ysmef.geomodel.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;

/** Removes incomplete transfers left by a previous client run, once per cache root. */
final class TlmPackCacheCleanup {
    private TlmPackCacheCleanup() {}

    static int cleanOnce(Path root, Set<Path> cleanedRoots) throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("invalid server cache directory");
        }
        Path realRoot = root.toRealPath();
        if (!cleanedRoots.add(realRoot)) return 0;
        int removed = 0;
        try (var entries = Files.list(realRoot)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if ((name.startsWith(".download-") && name.endsWith(".part"))
                        || name.startsWith(".staging-")) {
                    deleteTree(entry);
                    removed++;
                }
            }
        }
        return removed;
    }

    private static void deleteTree(Path entry) throws IOException {
        // Files.walk does not follow symbolic links, including links inside staging directories.
        try (var walk = Files.walk(entry)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
