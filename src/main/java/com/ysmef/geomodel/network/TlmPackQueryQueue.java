package com.ysmef.geomodel.network;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Server-thread query admission for one connection. Workers only read the closed flag. */
final class TlmPackQueryQueue {
    static final int MAX_MODELS = 512;
    static final long DISPATCH_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    static final long REQUERY_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final Map<String, Long> eligibleAt = new HashMap<>();
    private final Set<String> pending = new LinkedHashSet<>();
    private String inFlight;
    private Long nextDispatch;
    private volatile boolean closed;

    void request(String modelId, long now) {
        if (closed || (!eligibleAt.containsKey(modelId) && eligibleAt.size() >= MAX_MODELS)) return;
        eligibleAt.putIfAbsent(modelId, now);
        pending.add(modelId);
    }

    String poll(long now) {
        if (closed || inFlight != null || (nextDispatch != null && now - nextDispatch < 0)) return null;
        for (String modelId : pending) {
            if (now - eligibleAt.get(modelId) < 0) continue;
            inFlight = modelId;
            nextDispatch = now + DISPATCH_INTERVAL_NANOS;
            return modelId;
        }
        return null;
    }

    void complete(long now) {
        pending.remove(inFlight);
        eligibleAt.put(inFlight, now + REQUERY_INTERVAL_NANOS);
        inFlight = null;
    }

    /** Keep the request in its original place if the shared executor is full. */
    void retry() {
        inFlight = null;
    }

    boolean isClosed() {
        return closed;
    }

    void close() {
        closed = true;
        pending.clear();
        eligibleAt.clear();
        inFlight = null;
    }
}
