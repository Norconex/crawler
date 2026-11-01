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
package com.norconex.crawler.core.session;

import java.util.function.Supplier;

import com.norconex.crawler.core.CrawlerException;
import com.norconex.crawler.core.cluster.CacheMap;
import com.norconex.crawler.core.cluster.SerializedRecord;
import com.norconex.crawler.core.util.SerialUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Coordinates once-per-session and once-per-run task deduplication using the
 * distributed cache, and exposes the run-scoped attribute store.
 * Can be injected directly in tests without the full {@link CrawlerSession}.
 */
@RequiredArgsConstructor
@Slf4j
public class CrawlerRunCoordinator {

    private final CacheMap<String> sessionCache;
    private final CacheMap<String> runCache;
    private final CrawlerAttributes crawlRunAttributes;

    /**
     * Runs {@code runnable} exactly once per crawl session (across all nodes).
     * Subsequent calls with the same {@code taskId} are no-ops.
     * @param taskId  unique identifier for the task
     * @param runnable the task to run once
     */
    public void oncePerSession(String taskId, Runnable runnable) {
        oncePerCache(sessionCache, taskId, runnable);
    }

    /**
     * Computes a value exactly once per crawl session (across all nodes) and
     * returns the cached result on subsequent calls.
     * @param <T>      the value type
     * @param taskId   unique identifier for the computation
     * @param supplier the computation to run once
     * @return the value produced by the supplier (possibly {@code null})
     */
    public <T> T oncePerSessionAndGet(String taskId, Supplier<T> supplier) {
        return oncePerCacheAndGet(sessionCache, taskId, supplier);
    }

    /**
     * Runs {@code runnable} exactly once per crawl run (across all nodes).
     * Subsequent calls with the same {@code taskId} are no-ops.
     * @param taskId   unique identifier for the task
     * @param runnable the task to run once
     */
    public void oncePerRun(String taskId, Runnable runnable) {
        oncePerCache(runCache, taskId, runnable);
    }

    /**
     * Computes a value exactly once per crawl run (across all nodes) and
     * returns the cached result on subsequent calls.
     * @param <T>      the value type
     * @param taskId   unique identifier for the computation
     * @param supplier the computation to run once
     * @return the value produced by the supplier (possibly {@code null})
     */
    public <T> T oncePerRunAndGet(String taskId, Supplier<T> supplier) {
        return oncePerCacheAndGet(runCache, taskId, supplier);
    }

    /**
     * Returns the run-scoped attribute store backed by the run cache.
     * @return crawl-run attributes
     */
    public CrawlerAttributes getCrawlRunAttributes() {
        return crawlRunAttributes;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void oncePerCache(
            CacheMap<String> cache, String taskId, Runnable runnable) {
        var key = "once-" + taskId;
        cache.computeIfAbsent(key, k -> {
            runnable.run();
            return "1";
        });
    }

    private <T> T oncePerCacheAndGet(
            CacheMap<String> cache, String taskId, Supplier<T> supplier) {
        var key = "once-get-" + taskId;
        var stored = cache.computeIfAbsent(key, k ->
        // Always store a SerializedRecord JSON — handles null transparently.
        SerialUtil.toJsonString(SerializedRecord.wrap(supplier.get())));
        if (stored == null) {
            return null;
        }
        // Explicit type argument: without it the IDE infers a non-null result
        // from the class literal and reports the null check as dead code.
        var rec = SerialUtil.<SerializedRecord>fromJson(
                stored, SerializedRecord.class);
        if (rec == null) {
            return null;
        }
        try {
            return rec.unwrap();
        } catch (CrawlerException e) {
            LOG.warn("Could not resolve class for JSON-wrapped value of key"
                    + " '{}': {}. Returning null.", key, e.getMessage());
            return null;
        }
    }
}
