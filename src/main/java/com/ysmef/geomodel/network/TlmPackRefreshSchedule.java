package com.ysmef.geomodel.network;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Main-thread schedule for bounded, periodic version queries of rendered maid models. */
final class TlmPackRefreshSchedule {
    static final long INTERVAL_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
    private final Map<String, Long> nextQuery = new LinkedHashMap<>();

    void track(String modelId, long now) {
        nextQuery.put(modelId, now + INTERVAL_NANOS);
    }

    void refreshNow() {
        nextQuery.replaceAll((id, deadline) -> Long.MIN_VALUE);
    }

    String nextDue(long now, Predicate<String> eligible) {
        for (var entry : nextQuery.entrySet()) {
            if (now >= entry.getValue() && eligible.test(entry.getKey())) {
                entry.setValue(now + INTERVAL_NANOS);
                return entry.getKey();
            }
        }
        return null;
    }

    void clear() {
        nextQuery.clear();
    }
}
