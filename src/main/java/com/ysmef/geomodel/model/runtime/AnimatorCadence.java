package com.ysmef.geomodel.model.runtime;

/** Schedules one animator's distance-based evaluations against a monotonic clock. */
final class AnimatorCadence {
    private static final double NEAR_DISTANCE_SQUARED = 40.0 * 40.0;
    private static final double FAR_DISTANCE_SQUARED = 64.0 * 64.0;

    private int scheduledRate;
    private long nextEvaluation;

    /** Zero means every frame. Exactly 40 and 64 blocks stay in the closer band. */
    static int rateForDistance(double distanceSquared) {
        if (!(distanceSquared > NEAR_DISTANCE_SQUARED)) return 0;
        return distanceSquared > FAR_DISTANCE_SQUARED ? 10 : 30;
    }

    boolean isDue(long now, int rateHz) {
        return rateHz == 0 || scheduledRate != rateHz || now - nextEvaluation >= 0;
    }

    /** Advance only when an evaluation ran or was accepted by the worker. */
    void evaluated(long now, int rateHz) {
        if (rateHz == 0) {
            scheduledRate = 0;
            return;
        }
        long period = 1_000_000_000L / rateHz;
        if (scheduledRate != rateHz) {
            nextEvaluation = now + period;
            scheduledRate = rateHz;
            return;
        }
        long elapsed = now - nextEvaluation;
        if (elapsed >= 0) {
            // Keep the original phase even when a frame is late. Skip missed samples.
            nextEvaluation = now + period - elapsed % period;
        }
    }
}
