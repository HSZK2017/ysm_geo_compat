package com.ysmef.geomodel.model;

import java.util.Queue;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Runs queued work for one frame, leaving the remaining tasks for the next frame. */
final class FrameTaskDrain {
    private FrameTaskDrain() {}

    static <T> void drain(Queue<T> queue, Consumer<T> action, LongSupplier clock, long budgetNanos) {
        long start = clock.getAsLong();
        do {
            T task = queue.poll();
            if (task == null) return;
            action.accept(task);
        } while (clock.getAsLong() - start < budgetNanos);
    }
}
