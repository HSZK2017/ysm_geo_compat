package com.ysmef.geomodel.model.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class YSMRuntimePreloadTest {
    @SuppressWarnings("unchecked")
    private static <T> Map<String, T> state(String fieldName) throws Exception {
        Field field = YSMRuntimeModel.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<String, T>) field.get(null);
    }

    @Test
    void failureBackoffAppliesToRenderThreadLookup() throws Exception {
        String modelId = "missing_" + UUID.randomUUID();
        try {
            assertNull(YSMRuntimeModel.get(modelId));
            Long firstDeadline = YSMRuntimePreloadTest.<Long>state("FAILED_UNTIL").get(modelId);
            assertNotNull(firstDeadline);
            assertNull(YSMRuntimeModel.get(modelId));
            assertEquals(firstDeadline, YSMRuntimePreloadTest.<Long>state("FAILED_UNTIL").get(modelId));
        } finally {
            YSMRuntimeModel.invalidate(modelId);
        }
    }

    @Test
    void invalidatedWorkerCannotRemoveItsReplacement() throws Exception {
        String modelId = "missing_" + UUID.randomUUID();
        List<Runnable> queued = new ArrayList<>();
        Executor manualExecutor = queued::add;
        try {
            YSMRuntimeModel.preloadAsync(modelId, manualExecutor);
            assertEquals(1, queued.size());
            Object oldTicket = YSMRuntimePreloadTest.<Object>state("PRELOADING").get(modelId);
            YSMRuntimeModel.invalidateAll();
            YSMRuntimeModel.preloadAsync(modelId, manualExecutor);
            assertEquals(2, queued.size());
            Object newTicket = YSMRuntimePreloadTest.<Object>state("PRELOADING").get(modelId);
            assertNotNull(newTicket);
            assertFalse(oldTicket == newTicket);
            queued.get(0).run();
            assertSame(newTicket, YSMRuntimePreloadTest.<Object>state("PRELOADING").get(modelId));
            assertNull(YSMRuntimePreloadTest.<Long>state("FAILED_UNTIL").get(modelId));
            queued.get(1).run();
            assertFalse(YSMRuntimePreloadTest.<Object>state("PRELOADING").containsKey(modelId));
            assertNotNull(YSMRuntimePreloadTest.<Long>state("FAILED_UNTIL").get(modelId));
        } finally {
            YSMRuntimeModel.invalidate(modelId);
        }
    }

    @Test
    void duplicatePreloadsShareOneTicketAndIndividualInvalidationRevokesIt() throws Exception {
        String modelId = "missing_" + UUID.randomUUID();
        List<Runnable> queued = new ArrayList<>();
        Executor manualExecutor = queued::add;
        try {
            YSMRuntimeModel.preloadAsync(modelId, manualExecutor);
            Object firstTicket = YSMRuntimePreloadTest.<Object>state("PRELOADING").get(modelId);
            YSMRuntimeModel.preloadAsync(modelId, manualExecutor);
            assertEquals(1, queued.size());
            YSMRuntimeModel.invalidate(modelId);
            YSMRuntimeModel.preloadAsync(modelId, manualExecutor);
            assertEquals(2, queued.size());
            Object replacement = YSMRuntimePreloadTest.<Object>state("PRELOADING").get(modelId);
            assertFalse(firstTicket == replacement);
            queued.get(0).run();
            assertSame(replacement, YSMRuntimePreloadTest.<Object>state("PRELOADING").get(modelId));
            queued.get(1).run();
            assertFalse(YSMRuntimePreloadTest.<Object>state("PRELOADING").containsKey(modelId));
        } finally {
            YSMRuntimeModel.invalidate(modelId);
        }
    }

    @Test
    void expiredFailureDeadlineAllowsARealRetry() throws Exception {
        String modelId = "missing_" + UUID.randomUUID();
        try {
            assertNull(YSMRuntimeModel.get(modelId));
            Map<String, Long> failedUntil = state("FAILED_UNTIL");
            Long first = failedUntil.get(modelId);
            assertNotNull(first);
            failedUntil.put(modelId, System.nanoTime() - 1);
            assertNull(YSMRuntimeModel.get(modelId));
            Long second = failedUntil.get(modelId);
            assertNotNull(second);
            assertFalse(second <= first);
        } finally {
            YSMRuntimeModel.invalidate(modelId);
        }
    }
}
