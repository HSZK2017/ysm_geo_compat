package com.ysmef.geomodel.model;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Only server packages confirmed during the current connection override local TLM packs. */
public final class TlmServerOverlay {
    private static final Map<String, Path> ACTIVE = new LinkedHashMap<>();

    private TlmServerOverlay() {}

    public static synchronized boolean activate(String hash, Path zip) {
        if (!TlmPackArchive.validSha256(hash)) return false;
        Path resolved = zip.toAbsolutePath().normalize();
        if (resolved.equals(ACTIVE.get(hash))) return false;
        ACTIVE.put(hash, resolved);
        return true;
    }

    public static synchronized List<Path> activePacks() {
        return List.copyOf(ACTIVE.values());
    }

    public static synchronized Path activePack(String hash) {
        return ACTIVE.get(hash);
    }

    /** Drop versions no tracked maid model still uses, including a removed server pack. */
    public static synchronized boolean retainHashes(Set<String> hashes) {
        return ACTIVE.keySet().removeIf(hash -> !hashes.contains(hash));
    }

    public static synchronized boolean clear() {
        boolean changed = !ACTIVE.isEmpty();
        ACTIVE.clear();
        return changed;
    }
}
