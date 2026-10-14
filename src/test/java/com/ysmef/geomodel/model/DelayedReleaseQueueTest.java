package com.ysmef.geomodel.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DelayedReleaseQueueTest {
    @Test
    void reloadReleasesPendingResourcesExactlyOnce() {
        List<String> released = new ArrayList<>();
        DelayedReleaseQueue<String> queue = new DelayedReleaseQueue<>(5, released::add);
        queue.schedule("mesh");
        queue.processTick();
        queue.releaseAll();
        queue.releaseAll();
        for (int i = 0; i < 5; i++) {
            queue.processTick();
        }
        assertEquals(List.of("mesh"), released);
    }

    @Test
    void ordinaryEvictionKeepsTheFullDelay() {
        List<String> released = new ArrayList<>();
        DelayedReleaseQueue<String> queue = new DelayedReleaseQueue<>(5, released::add);
        queue.schedule("mesh");
        for (int i = 0; i < 4; i++) {
            queue.processTick();
        }
        assertEquals(List.of(), released);
        queue.processTick();
        assertEquals(List.of("mesh"), released);
    }

    @Test
    void reschedulingRestartsTheDelayWithoutDoubleRelease() {
        List<String> released = new ArrayList<>();
        DelayedReleaseQueue<String> queue = new DelayedReleaseQueue<>(3, released::add);
        queue.schedule("mesh");
        queue.processTick();
        queue.processTick();
        queue.schedule("mesh");
        queue.processTick();
        assertEquals(List.of(), released);
        queue.processTick();
        assertEquals(List.of(), released);
        queue.processTick();
        queue.releaseAll();
        assertEquals(List.of("mesh"), released);
    }
}
