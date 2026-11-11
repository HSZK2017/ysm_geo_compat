package com.ysmef.geomodel.network;

import org.junit.jupiter.api.Test;

import static com.ysmef.geomodel.network.TlmPackQueryQueue.DISPATCH_INTERVAL_NANOS;
import static com.ysmef.geomodel.network.TlmPackQueryQueue.MAX_MODELS;
import static com.ysmef.geomodel.network.TlmPackQueryQueue.REQUERY_INTERVAL_NANOS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlmPackQueryQueueTest {
    @Test
    void slowQueriesCoalesceDuplicatesAndAllowPeriodicRefresh() {
        TlmPackQueryQueue queue = new TlmPackQueryQueue();
        long completedAt = 2 * REQUERY_INTERVAL_NANOS;
        queue.request("maid:first", 0);
        assertEquals("maid:first", queue.poll(0));
        queue.request("maid:first", REQUERY_INTERVAL_NANOS);
        queue.request("maid:second", REQUERY_INTERVAL_NANOS);
        assertNull(queue.poll(completedAt));

        queue.complete(completedAt);
        assertEquals("maid:second", queue.poll(completedAt));
        queue.complete(completedAt);
        assertNull(queue.poll(completedAt + REQUERY_INTERVAL_NANOS));

        queue.request("maid:first", completedAt + 1);
        assertNull(queue.poll(completedAt + REQUERY_INTERVAL_NANOS - 1));
        assertEquals("maid:first", queue.poll(completedAt + REQUERY_INTERVAL_NANOS));
    }

    @Test
    void executorRejectionRetainsTheFirstRequestAndPacesRetry() {
        TlmPackQueryQueue queue = new TlmPackQueryQueue();
        queue.request("maid:first", 0);
        queue.request("maid:second", 0);
        assertEquals("maid:first", queue.poll(0));
        queue.retry();
        assertNull(queue.poll(DISPATCH_INTERVAL_NANOS - 1));
        assertEquals("maid:first", queue.poll(DISPATCH_INTERVAL_NANOS));
        queue.complete(DISPATCH_INTERVAL_NANOS);
        assertEquals("maid:second", queue.poll(2 * DISPATCH_INTERVAL_NANOS));
    }

    @Test
    void connectionCapsDistinctModelsAndStopsDispatchingOnClose() {
        TlmPackQueryQueue queue = new TlmPackQueryQueue();
        for (int i = 0; i <= MAX_MODELS; i++) queue.request("maid:model_" + i, 0);
        for (int i = 0; i < MAX_MODELS; i++) {
            long now = i * DISPATCH_INTERVAL_NANOS;
            assertEquals("maid:model_" + i, queue.poll(now));
            queue.complete(now);
        }
        long now = MAX_MODELS * DISPATCH_INTERVAL_NANOS;
        assertNull(queue.poll(now));

        queue.request("maid:model_0", now);
        assertEquals("maid:model_0", queue.poll(now));
        queue.request("maid:model_1", now);
        queue.close();
        queue.retry();
        queue.request("maid:model_2", now);
        assertTrue(queue.isClosed());
        assertNull(queue.poll(now + REQUERY_INTERVAL_NANOS));
    }
}
