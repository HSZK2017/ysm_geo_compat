package com.ysmef.geomodel.model.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class AnimatorCacheTest {
    @Test
    void tickRestartReplacesTheAnimatorAndEqualTicksReuseIt() {
        var cache = new AnimatorCache<Object, Object>(Object::new);
        var id = UUID.randomUUID();
        var entity = new Object();
        Object old = cache.get(id, entity, 200, 0);
        assertSame(old, cache.get(id, entity, 200, 1));
        assertSame(old, cache.get(id, entity, 201, 2));

        Object restarted = cache.get(id, entity, 1, 3);
        assertNotSame(old, restarted);
        assertSame(restarted, cache.get(id, entity, 2, 4));
    }

    @Test
    void aReplacementEntityAndADisconnectBothStartWithFreshState() {
        var cache = new AnimatorCache<Object, Object>(Object::new);
        var id = UUID.randomUUID();
        Object before = cache.get(id, new Object(), 200, 0);
        var replacement = new Object();
        Object after = cache.get(id, replacement, 201, 1);
        assertNotSame(before, after);

        cache.clear();
        assertNotSame(after, cache.get(id, replacement, 202, 2));
    }

    @Test
    void monotonicSweepExpiresOnlyIdleAnimatorsEvenAcrossClockWrap() {
        var cache = new AnimatorCache<Object, Object>(Object::new);
        var idleId = UUID.randomUUID();
        var liveId = UUID.randomUUID();
        var idle = new Object();
        var live = new Object();
        long start = Long.MAX_VALUE - AnimatorCache.IDLE_NANOS / 2;
        Object idleAnimator = cache.get(idleId, idle, 5, start);
        Object liveAnimator = cache.get(liveId, live, 5, start);
        assertSame(liveAnimator, cache.get(liveId, live, 6, start + 1));

        long expiresAt = start + AnimatorCache.IDLE_NANOS;
        cache.sweep(expiresAt);
        assertNotSame(idleAnimator, cache.get(idleId, idle, 5, expiresAt));
        assertSame(liveAnimator, cache.get(liveId, live, 6, expiresAt));
    }
}
