package com.ysmef.geomodel.network;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Main-thread admission window, kept below the server's per-player download queue limit. */
final class TlmDownloadWindow {
    private final int limit;
    private final ArrayDeque<String> queued = new ArrayDeque<>();
    private final Set<String> known = new HashSet<>();
    private final Set<String> active = new HashSet<>();

    TlmDownloadWindow(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        this.limit = limit;
    }

    void enqueue(String modelId) {
        if (known.add(modelId)) queued.add(modelId);
    }

    String poll() {
        if (active.size() >= limit) return null;
        String modelId = queued.poll();
        if (modelId != null) active.add(modelId);
        return modelId;
    }

    void remove(String modelId) {
        if (known.remove(modelId)) {
            active.remove(modelId);
            queued.remove(modelId);
        }
    }

    void clear() {
        queued.clear();
        known.clear();
        active.clear();
    }
}
