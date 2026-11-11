package com.ysmef.geomodel.model.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimatorCadenceTest {
    private static final long MILLISECOND = 1_000_000L;

    @Test
    void distanceBandsAndBandChangesUseTheirOwnCadence() {
        int near = AnimatorCadence.rateForDistance(40 * 40);
        int mid = AnimatorCadence.rateForDistance(64 * 64);
        int far = AnimatorCadence.rateForDistance(64 * 64 + 1);
        assertEquals(0, near);
        assertEquals(30, AnimatorCadence.rateForDistance(40 * 40 + 1));
        assertEquals(30, mid);
        assertEquals(10, far);

        var cadence = new AnimatorCadence();
        assertTrue(cadence.isDue(0, mid));
        cadence.evaluated(0, mid);
        assertFalse(cadence.isDue(MILLISECOND, mid));
        assertTrue(cadence.isDue(MILLISECOND, far));
        cadence.evaluated(MILLISECOND, far);
        assertFalse(cadence.isDue(2 * MILLISECOND, far));
        assertTrue(cadence.isDue(2 * MILLISECOND, near));
        cadence.evaluated(2 * MILLISECOND, near);
        assertTrue(cadence.isDue(3 * MILLISECOND, far));
    }

    @Test
    void simulatedFrameRatesKeepTheTargetCadenceWhenFramesAreAvailable() {
        for (int fps : new int[]{24, 30, 60, 144, 240}) {
            for (int rate : new int[]{30, 10}) {
                var cadence = new AnimatorCadence();
                int evaluations = 0;
                for (int frame = 0; frame < 10 * fps; frame++) {
                    long now = frame * 1_000_000_000L / fps;
                    if (cadence.isDue(now, rate)) {
                        cadence.evaluated(now, rate);
                        evaluations++;
                    }
                }
                assertEquals(10 * Math.min(fps, rate), evaluations, "fps=" + fps + ", Hz=" + rate);
            }
        }
    }

    @Test
    void missedFramesAreCoalescedAndABusyWorkerDoesNotConsumeTheDeadline() {
        var cadence = new AnimatorCadence();
        cadence.evaluated(0, 10);
        assertTrue(cadence.isDue(100 * MILLISECOND, 10));
        // The worker did not accept that frame. The next available frame is still due.
        assertTrue(cadence.isDue(350 * MILLISECOND, 10));
        cadence.evaluated(350 * MILLISECOND, 10);
        assertFalse(cadence.isDue(350 * MILLISECOND, 10));
        assertFalse(cadence.isDue(400 * MILLISECOND - 1, 10));
        assertTrue(cadence.isDue(400 * MILLISECOND, 10));
    }
}
