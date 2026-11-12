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
package com.norconex.crawler.core.cluster.impl.mvstore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.h2.mvstore.MVStore;

import com.norconex.crawler.core.cluster.CacheManager;
import com.norconex.crawler.core.cluster.CacheMap;
import com.norconex.crawler.core.cluster.CacheNames;
import com.norconex.crawler.core.cluster.CacheQueue;
import com.norconex.crawler.core.cluster.CacheSet;
import com.norconex.crawler.core.cluster.SerializedCache;
import com.norconex.crawler.core.cluster.SerializedCache.CacheType;
import com.norconex.crawler.core.cluster.SerializedCache.SerializedEntry;
import com.norconex.crawler.core.cluster.impl.hazelcast.event.CacheEntryChangeListener;
import com.norconex.crawler.core.cluster.impl.memory.InMemoryCacheMap;
import com.norconex.crawler.core.cluster.impl.memory.InMemoryCacheQueue;
import com.norconex.crawler.core.cluster.impl.memory.InMemoryCacheSet;
import com.norconex.crawler.core.cluster.pipeline.StepRecord;
import com.norconex.crawler.core.util.SerialUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link CacheManager} backed by H2 MVStore for persistent caches and
 * in-memory structures for ephemeral caches ({@code eph-*}).
 *
 * <p>Persistent caches are stored in a single MVStore file at
 * {@code {workDir}/mvstore.db}. Ephemeral caches (names starting with
 * {@code eph-}) use plain in-memory data structures — they do not
 * survive JVM restarts, matching the behavior of Hazelcast's ephemeral
 * caches.</p>
 */
@Slf4j
public class MVStoreCacheManager implements CacheManager {

    static final String MVSTORE_FILE_NAME = "mvstore.db";
    private static final String EPH_PREFIX = "eph-";

    private MVStore store;
    private Path storePath;

    // Cached wrapper instances (keyed by cache name)
    private final Map<String, CacheMap<?>> maps = new ConcurrentHashMap<>();
    private final Map<String, CacheQueue<?>> queues =
            new ConcurrentHashMap<>();
    private final Map<String, CacheSet> sets = new ConcurrentHashMap<>();

    // Ephemeral caches (in-memory, not file-backed)
    private final Map<String, InMemoryCacheMap<?>> ephMaps =
            new ConcurrentHashMap<>();
    private final Map<String, InMemoryCacheQueue<?>> ephQueues =
            new ConcurrentHashMap<>();
    private final Map<String, InMemoryCacheSet> ephSets =
            new ConcurrentHashMap<>();

    /**
     * Opens the MVStore file and prepares caches. Must be called before
     * any cache access.
     * @param workDir the crawler work directory
     * @param config the MVStore configuration
     */
    public void open(Path workDir, MVStoreClusterConnectorConfig config) {
        storePath = workDir.resolve(MVSTORE_FILE_NAME);
        LOG.debug("Opening MVStore at: {}", storePath);
        var builder = new MVStore.Builder()
                .fileName(storePath.toString())
                .pageSplitSize(config.getPageSplitSize())
                .cacheSize(config.getCacheSize())
                .autoCompactFillRate(config.getAutoCompactFillRate())
                .autoCommitBufferSize(config.getAutoCommitBufferSize());
        if (config.getCompress() == 1) {
            builder.compress();
        } else if (config.getCompress() >= 2) {
            builder.compressHigh();
        }
        store = builder.open();
        store.setAutoCommitDelay(config.getAutoCommitDelay());
        LOG.debug("MVStore opened successfully.");
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> CacheMap<T> getCacheMap(String name, Class<T> valueType) {
        if (isEphemeral(name)) {
            return (CacheMap<T>) ephMaps.computeIfAbsent(
                    name, InMemoryCacheMap::new);
        }
        return (CacheMap<T>) maps.computeIfAbsent(name, n -> {
            var mvMap = store.<String, String>openMap("map." + n);
            return new MVStoreCacheMap<>(mvMap, valueType, n, true);
        });
    }

    @Override
    public CacheSet getCacheSet(String name) {
        if (isEphemeral(name)) {
            return ephSets.computeIfAbsent(
                    name, n -> new InMemoryCacheSet());
        }
        return sets.computeIfAbsent(name, n -> {
            var mvMap = store.<String, Boolean>openMap("set." + n);
            return new MVStoreCacheSet(mvMap);
        });
    }

    @Override
    public boolean cacheExists(String name) {
        if (isEphemeral(name)) {
            var map = ephMaps.get(name);
            return map != null && !map.isEmpty();
        }
        var map = maps.get(name);
        return map != null && !map.isEmpty();
    }

    @Override
    public void exportCaches(Consumer<SerializedCache> c) {
        // Only export persistent (file-backed) map caches
        maps.forEach((name, cacheMap) -> {
            var serialCache = new SerializedCache();
            serialCache.setCacheName(name);
            serialCache.setCacheType(CacheType.MAP);
            serialCache.setPersistent(true);
            var entries = new java.util.ArrayList<SerializedEntry>();
            cacheMap.forEach((key, value) -> {
                if (value instanceof String strVal) {
                    entries.add(new SerializedEntry(key, strVal));
                } else {
                    entries.add(new SerializedEntry(
                            key, SerialUtil.toJsonString(value)));
                }
            });
            serialCache.setEntries(entries.iterator());
            c.accept(serialCache);
        });
    }

    @Override
    public void importCaches(List<SerializedCache> caches) {
        for (var cache : caches) {
            if (cache.getCacheType() == CacheType.MAP) {
                var cacheMap = (MVStoreCacheMap<String>) getCacheMap(
                        cache.getCacheName(), String.class);
                cache.forEach(
                        entry -> cacheMap.put(entry.getKey(), entry.getJson()));
            }
        }
    }

    @Override
    public void clearCaches() {
        maps.values().forEach(CacheMap::clear);
        queues.values().forEach(CacheQueue::clear);
        sets.values().forEach(CacheSet::clear);
        ephMaps.values().forEach(InMemoryCacheMap::clear);
        ephQueues.values().forEach(InMemoryCacheQueue::clear);
        ephSets.values().forEach(InMemoryCacheSet::clear);
    }

    @Override
    public CacheMap<String> getCrawlerCache() {
        return getCacheMap(CacheNames.CRAWLER, String.class);
    }

    @Override
    public CacheMap<String> getCrawlSessionCache() {
        return getCacheMap(CacheNames.CRAWL_SESSION, String.class);
    }

    @Override
    public CacheMap<String> getCrawlRunCache() {
        return getCacheMap(CacheNames.CRAWL_RUN, String.class);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> CacheQueue<T> getCacheQueue(String name, Class<T> valueType) {
        if (isEphemeral(name)) {
            return (CacheQueue<T>) ephQueues.computeIfAbsent(
                    name, InMemoryCacheQueue::new);
        }
        return (CacheQueue<T>) queues.computeIfAbsent(name, n -> {
            var mvMap = store.<Long, String>openMap("queue." + n);
            return new MVStoreCacheQueue<>(mvMap, valueType, n);
        });
    }

    @Override
    public CacheMap<String> getAdminCache() {
        return getCacheMap(CacheNames.ADMIN, String.class);
    }

    @Override
    public CacheMap<StepRecord> getPipelineStepCache() {
        return getCacheMap(
                CacheNames.PIPE_CURRENT_STEP, StepRecord.class);
    }

    @Override
    public CacheMap<StepRecord> getPipelineWorkerStatusCache() {
        return getCacheMap(
                CacheNames.PIPE_WORKER_STATUSES, StepRecord.class);
    }

    @Override
    public <T> void addCacheEntryChangeListener(
            CacheEntryChangeListener<T> listener, String cacheName) {
        // No-op: single-node, no distributed events.
    }

    @Override
    public <T> void removeCacheEntryChangeListener(
            CacheEntryChangeListener<T> listener, String cacheName) {
        // No-op
    }

    @Override
    public Object vendor() {
        return store;
    }

    /**
     * Commits pending changes and closes the MVStore.
     * <p>The background auto-commit thread is stopped first to prevent
     * it from racing with the manual compact operation.</p>
     */
    public void close() {
        if (store == null || store.isClosed()) {
            return;
        }
        LOG.debug("Closing MVStore at: {}", storePath);
        try {
            // Stop the background auto-commit/compact thread first
            // so it does not race with our manual compactFile call.
            store.setAutoCommitDelay(0);
            store.commit();
            store.compactFile(5000);
        } catch (Exception | AssertionError e) {
            LOG.warn(
                    "Error compacting MVStore (data is safe): {}",
                    e.getMessage(), e);
        } finally {
            try {
                store.close();
            } catch (Exception | AssertionError e) {
                LOG.warn(
                        "Error closing MVStore: {}",
                        e.getMessage(), e);
            }
        }
        // Invalidate cached wrappers so that the next open() creates fresh
        // ones pointing to the new MVStore instance. Without this, stale
        // wrappers referencing the closed store would be returned by
        // computeIfAbsent, causing "Map is closed" exceptions on re-use.
        maps.clear();
        queues.clear();
        sets.clear();
        LOG.debug("MVStore closed.");
    }

    /**
     * Gets the ephemeral run cache (for clearing on close).
     */
    CacheMap<String> getEphRunCache() {
        return getCrawlRunCache();
    }

    /**
     * Gets the ephemeral admin cache (for clearing on close).
     */
    CacheMap<String> getEphAdminCache() {
        return getAdminCache();
    }

    private static boolean isEphemeral(String name) {
        return name.startsWith(EPH_PREFIX);
    }
}
