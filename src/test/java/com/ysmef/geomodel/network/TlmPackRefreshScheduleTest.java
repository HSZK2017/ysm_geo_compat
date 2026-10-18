package com.ysmef.geomodel.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TlmPackRefreshScheduleTest {
    @Test
    void trackedModelsAreQueriedPeriodicallyAndReloadCanRequestAnImmediateCheck() {
        TlmPackRefreshSchedule schedule = new TlmPackRefreshSchedule();
        long start = 1_000_000_000L;
        schedule.track("maid:first", start);
        schedule.track("maid:second", start);
        assertNull(schedule.nextDue(start + TlmPackRefreshSchedule.INTERVAL_NANOS - 1, id -> true));
        assertEquals("maid:first", schedule.nextDue(start + TlmPackRefreshSchedule.INTERVAL_NANOS, id -> true));
        assertEquals("maid:second", schedule.nextDue(start + TlmPackRefreshSchedule.INTERVAL_NANOS, id -> true));
        assertNull(schedule.nextDue(start + TlmPackRefreshSchedule.INTERVAL_NANOS, id -> true));

        schedule.refreshNow();
        assertEquals("maid:second", schedule.nextDue(start + TlmPackRefreshSchedule.INTERVAL_NANOS,
                id -> id.equals("maid:second")));
        assertEquals("maid:first", schedule.nextDue(start + TlmPackRefreshSchedule.INTERVAL_NANOS, id -> true));
        schedule.clear();
        assertNull(schedule.nextDue(Long.MAX_VALUE, id -> true));
    }
}
