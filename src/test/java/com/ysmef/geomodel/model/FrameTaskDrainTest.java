package com.ysmef.geomodel.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameTaskDrainTest {
    @Test
    void discardedUploadsStillConsumeBudgetAndRemainingTasksWaitForNextFrame() {
        var tasks = new ArrayDeque<>(List.of("stale", "current", "later"));
        var uploaded = new ArrayList<String>();
        var clock = new AtomicLong();
        Consumer<String> upload = task -> {
            clock.addAndGet(6);
            if (!task.equals("stale")) uploaded.add(task);
        };

        FrameTaskDrain.drain(tasks, upload, clock::get, 10);
        assertEquals(List.of("current"), uploaded);
        assertEquals("later", tasks.peek());

        FrameTaskDrain.drain(tasks, upload, clock::get, 10);
        assertEquals(List.of("current", "later"), uploaded);
        assertTrue(tasks.isEmpty());
    }
}
