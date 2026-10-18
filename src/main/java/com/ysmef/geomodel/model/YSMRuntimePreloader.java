package com.ysmef.geomodel.model;

import com.ysmef.geomodel.model.runtime.YSMRuntimeModel;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Schedules compiled runtime scripts before a newly registered mesh is drawn. */
final class YSMRuntimePreloader {
    private static final ExecutorService PRELOAD_POOL = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())), runnable -> {
                Thread thread = new Thread(runnable, "ysm-geo-preload");
                thread.setDaemon(true);
                return thread;
            });

    private YSMRuntimePreloader() {}

    static void preloadAsync(String modelId) {
        YSMRuntimeModel.preloadAsync(modelId, PRELOAD_POOL);
    }
}
