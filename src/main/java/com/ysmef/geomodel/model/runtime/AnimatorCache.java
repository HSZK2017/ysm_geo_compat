package com.ysmef.geomodel.model.runtime;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Keeps animation state for the current incarnation of each rendered entity. */
final class AnimatorCache<E, A> {
    static final long IDLE_NANOS = TimeUnit.SECONDS.toNanos(60);

    private static final class Entry<E, A> {
        final WeakReference<E> entity;
        final A animator;
        int tick;
        long lastUsedAt;

        Entry(E entity, A animator) {
            this.entity = new WeakReference<>(entity);
            this.animator = animator;
        }
    }

    private final Map<UUID, Entry<E, A>> entries = new HashMap<>();
    private final Supplier<A> createAnimator;

    AnimatorCache(Supplier<A> createAnimator) {
        this.createAnimator = createAnimator;
    }

    synchronized A get(UUID uuid, E entity, int tick, long now) {
        Entry<E, A> entry = entries.get(uuid);
        if (entry == null || !entry.entity.refersTo(entity) || tick < entry.tick) {
            // An old worker may still publish to its animator. Give the new
            // timeline its own state and buffers so that result cannot leak in.
            entry = new Entry<>(entity, createAnimator.get());
            entries.put(uuid, entry);
        }
        entry.tick = tick;
        entry.lastUsedAt = now;
        return entry.animator;
    }

    synchronized void sweep(long now) {
        entries.values().removeIf(entry -> now - entry.lastUsedAt >= IDLE_NANOS);
    }

    synchronized void clear() {
        entries.clear();
    }
}
