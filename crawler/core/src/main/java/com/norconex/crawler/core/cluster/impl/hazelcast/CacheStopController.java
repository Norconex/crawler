/* Copyright 2025-2026 Norconex Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.norconex.crawler.core.cluster.impl.hazelcast;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.norconex.crawler.core.cluster.CacheMap;

import lombok.extern.slf4j.Slf4j;

/**
 * Cache-based stop controller.
 * Monitors a Hazelcast cache for stop signals.
 */
@Slf4j
class CacheStopController implements AutoCloseable {

    private final HazelcastCluster cluster;
    private final CacheMap<String> adminCache;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    private static final String STOP_KEY = "STOP";
    private static final long POLL_INTERVAL_MS = 1000;

    public CacheStopController(HazelcastCluster cluster) {
        this.cluster = cluster;
        adminCache = cluster.getCacheManager().getAdminCache();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "stop-poller");
            t.setDaemon(true);
            return t;
        });
    }

    /** Call during startup */
    public void init() {
        if (adminCache.containsKey(STOP_KEY)) {
            triggerLocalStop("Startup check");
            return;
        }
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                if (adminCache.containsKey(STOP_KEY)) {
                    triggerLocalStop("Polling check");
                }
            } catch (Exception e) {
                LOG.error("Stop poller failed.", e);
            }
        }, 1, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Call to clean up (optional if JVM exits anyway) */
    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private void triggerLocalStop(String source) {
        if (stopping.compareAndSet(false, true)) {
            LOG.info("STOP triggered via {}.", source);
            cluster.stopLocally();
        }
    }

    public void sendClusterStopSignal() {
        adminCache.putIfAbsent(STOP_KEY, "1");
    }
}
