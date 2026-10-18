package com.ysmef.geomodel.network;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlmDownloadWindowTest {
    @Test
    void moreThanServerQueueCapacityIsAdmittedInBoundedBatches() {
        TlmDownloadWindow window = new TlmDownloadWindow(8);
        for (int i = 0; i < 80; i++) window.enqueue("maid:" + i);
        window.enqueue("maid:0"); // A repeated render must not request the same pack twice.

        Set<String> dispatched = new HashSet<>();
        for (int batch = 0; batch < 10; batch++) {
            for (int i = 0; i < 8; i++) {
                String next = window.poll();
                assertTrue(dispatched.add(next));
            }
            assertNull(window.poll());
            for (int i = 0; i < 8; i++) window.remove("maid:" + (batch * 8 + i));
        }
        assertEquals(80, dispatched.size());
        assertNull(window.poll());
    }

    @Test
    void removingQueuedOrActiveRequestFreesItsPlaceAndDisconnectClearsBoth() {
        TlmDownloadWindow window = new TlmDownloadWindow(1);
        window.enqueue("maid:first");
        window.enqueue("maid:cancelled");
        window.enqueue("maid:last");
        assertEquals("maid:first", window.poll());
        window.remove("maid:cancelled");
        assertNull(window.poll());
        window.remove("maid:first");
        assertEquals("maid:last", window.poll());
        window.clear();
        window.enqueue("maid:new-session");
        assertEquals("maid:new-session", window.poll());
    }
}
