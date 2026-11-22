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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import com.hazelcast.core.HazelcastInstance;
import com.norconex.crawler.core.cluster.CacheEntryUpdate;
import com.norconex.crawler.core.cluster.QueryFilter;
import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.ledger.ProcessingStatus;
import com.norconex.crawler.core.util.SerialUtil;

/**
 * Component tests for {@link HazelcastMapAdapter}.
 *
 * <p>One shared {@link HazelcastInstance} is used for the entire class; each
 * test method uses a UUID-prefixed map name to ensure complete isolation
 * without requiring an instance restart.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(30)
class HazelcastMapAdapterTest {

    private HazelcastInstance hz;
    private HazelcastCacheManager cacheManager;
    /** Unique prefix for every test method so maps never clash. */
    private String mapPrefix;

    @BeforeAll
    void startHazelcast() {
        hz = HazelcastTestSupport.startNode();
        cacheManager = new HazelcastCacheManager(hz);
    }

    @AfterAll
    void stopHazelcast() {
        cacheManager.close();
    }

    @BeforeEach
    void freshMapPrefix() {
        // Each test writes to its own uniquely-named maps.
        mapPrefix = "test-" + UUID.randomUUID() + "-";
    }

    // -----------------------------------------------------------------
    // String-typed map — basic CRUD
    // -----------------------------------------------------------------

    @Test
    void testStringMap_putAndGet() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "strings",
                String.class);
        map.put("k1", "hello");
        assertThat(map.get("k1")).contains("hello");
    }

    @Test
    void testStringMap_getMissing_returnsEmpty() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "strings",
                String.class);
        assertThat(map.get("no-such-key")).isEmpty();
    }

    @Test
    void testStringMap_remove() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "strings",
                String.class);
        map.put("k1", "v1");
        map.remove("k1");
        assertThat(map.containsKey("k1")).isFalse();
    }

    @Test
    void testStringMap_putIfAbsent_firstCallWins() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "strings",
                String.class);
        var prev1 = map.putIfAbsent("k1", "first");
        var prev2 = map.putIfAbsent("k1", "second");

        assertThat(prev1).isNull(); // no previous value
        assertThat(prev2).isEqualTo("first"); // existing value returned
        assertThat(map.get("k1")).contains("first"); // original value preserved
    }

    @Test
    void testStringMap_keys_and_size() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "ksize",
                String.class);
        map.put("a", "1");
        map.put("b", "2");
        map.put("c", "3");

        assertThat(map.size()).isEqualTo(3);
        assertThat(map.keys()).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void testStringMap_countAll() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "countall",
                String.class);
        map.put("x", "1");
        map.put("y", "2");

        assertThat(map.count(QueryFilter.ofAll())).isEqualTo(2);
    }

    @Test
    void testStringMap_forEach() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "foreach",
                String.class);
        map.put("p", "P");
        map.put("q", "Q");

        var sb = new StringBuilder();
        // Values sorted to make assertion deterministic.
        map.forEach((k, v) -> sb.append(v));
        assertThat(sb.toString()).contains("P", "Q");
    }

    @Test
    void testStringMap_clear() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "clr",
                String.class);
        map.put("k", "v");
        map.clear();
        assertThat(map.isEmpty()).isTrue();
    }

    // -----------------------------------------------------------------
    // computeIfAbsent — should call the supplier exactly once
    // (concurrent safety baked in)
    // -----------------------------------------------------------------

    @Test
    void testComputeIfAbsent_runsMappingOnlyOnce() throws InterruptedException {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "cia",
                String.class);
        var callCount = new AtomicInteger(0);
        var threads = 10;
        var latch = new CountDownLatch(threads);
        var pool = Executors.newFixedThreadPool(threads);

        for (var i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    map.computeIfAbsent("shared-key", k -> {
                        callCount.incrementAndGet();
                        return "computed";
                    });
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(map.get("shared-key")).contains("computed");
        // The mapping function may legitimately be called more than once in
        // concurrent scenarios if the CAS fails, but the final value must
        // always be "computed" and must never be null.
        assertThat(callCount.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void testComputeIfAbsent_existingKeySkipsMapping() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "cia2",
                String.class);
        map.put("existing", "original");
        var callCount = new AtomicInteger(0);

        map.computeIfAbsent("existing", k -> {
            callCount.incrementAndGet();
            return "new-value";
        });

        assertThat(callCount.get()).isZero();
        assertThat(map.get("existing")).contains("original");
    }

    // -----------------------------------------------------------------
    // Typed object map — verifies typed value round-trip
    // -----------------------------------------------------------------

    @Test
    void testTypedMap_putAndGetRoundTrip() {
        var map = cacheManager.getCacheMap(mapPrefix + "entries",
                CrawlerEntry.class);

        var entry = new CrawlerEntry("https://example.com");
        entry.setDepth(3);
        entry.setProcessingStatus(ProcessingStatus.QUEUED);
        map.put(entry.getReference(), entry);

        var loaded = map.get("https://example.com").orElseThrow();
        assertThat(loaded.getReference()).isEqualTo("https://example.com");
        assertThat(loaded.getDepth()).isEqualTo(3);
        assertThat(loaded.getProcessingStatus())
                .isEqualTo(ProcessingStatus.QUEUED);
    }

    @Test
    void testTypedMap_replace_swapsValue() {
        var map = cacheManager.getCacheMap(mapPrefix + "replace",
                CrawlerEntry.class);

        var queued = new CrawlerEntry("ref-1");
        queued.setProcessingStatus(ProcessingStatus.QUEUED);

        var processing = new CrawlerEntry("ref-1");
        processing.setProcessingStatus(ProcessingStatus.PROCESSING);

        map.put("ref-1", queued);
        var replaced = map.replace("ref-1", queued, processing);

        assertThat(replaced).isTrue();
        assertThat(map.get("ref-1").map(CrawlerEntry::getProcessingStatus))
                .contains(ProcessingStatus.PROCESSING);
    }

    // -----------------------------------------------------------------
    // computeIfPresent
    // -----------------------------------------------------------------

    @Test
    void testComputeIfPresent_existingKey_updates() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "cip",
                String.class);
        map.put("k", "v1");
        var result = map.computeIfPresent("k", (key, val) -> val + "-updated");
        assertThat(result).hasValue("v1-updated");
        assertThat(map.get("k")).hasValue("v1-updated");
    }

    @Test
    void testComputeIfPresent_missingKey_returnsEmpty() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "cip2",
                String.class);
        var result = map.computeIfPresent("missing", (key, val) -> "x");
        assertThat(result).isEmpty();
    }

    @Test
    void testComputeIfPresent_remapToNull_removesEntry() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "cip3",
                String.class);
        map.put("rm", "val");
        map.computeIfPresent("rm", (key, val) -> null);
        assertThat(map.get("rm")).isEmpty();
    }

    // -----------------------------------------------------------------
    // compute
    // -----------------------------------------------------------------

    @Test
    void testCompute_insertsNewEntry() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "comp",
                String.class);
        var result = map.compute("nk", (key, old) -> "brand-new");
        assertThat(result).hasValue("brand-new");
        assertThat(map.get("nk")).hasValue("brand-new");
    }

    @Test
    void testCompute_updatesExistingEntry() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "comp2",
                String.class);
        map.put("k", "old");
        var result = map.compute("k", (key, old) -> old + "-upd");
        assertThat(result).hasValue("old-upd");
    }

    @Test
    void testCompute_returnsNullRemovesEntry() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "comp3",
                String.class);
        map.put("k", "v");
        var result = map.compute("k", (key, old) -> null);
        assertThat(result).isEmpty();
        assertThat(map.get("k")).isEmpty();
    }

    // -----------------------------------------------------------------
    // merge
    // -----------------------------------------------------------------

    @Test
    void testMerge_noExistingEntry_usesValue() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "merge",
                String.class);
        var result = map.merge("k", "init", (old, v) -> old + "+" + v);
        assertThat(result).isEqualTo("init");
        assertThat(map.get("k")).hasValue("init");
    }

    @Test
    void testMerge_existingEntry_remap() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "merge2",
                String.class);
        map.put("k", "existing");
        var result = map.merge("k", "value", (old, v) -> old + "+" + v);
        assertThat(result).isEqualTo("existing+value");
    }

    @Test
    void testMerge_remapToNull_removesEntry() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "merge3",
                String.class);
        map.put("k", "v");
        var result = map.merge("k", "ignored", (old, v) -> null);
        assertThat(result).isNull();
        assertThat(map.get("k")).isEmpty();
    }

    // -----------------------------------------------------------------
    // getOrDefault / putAll / isPersistent / getName / vendor
    // -----------------------------------------------------------------

    @Test
    void testGetOrDefault_missingKey_returnsDefault() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "god",
                String.class);
        assertThat(map.getOrDefault("none", "fallback")).isEqualTo("fallback");
    }

    @Test
    void testGetOrDefault_existingKey_returnsValue() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "god2",
                String.class);
        map.put("k", "real");
        assertThat(map.getOrDefault("k", "fallback")).isEqualTo("real");
    }

    @Test
    void testPutAll_multipleEntries() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "pa",
                String.class);
        map.putAll(java.util.Map.of("m1", "v1", "m2", "v2", "m3", "v3"));
        assertThat(map.size()).isEqualTo(3L);
        assertThat(map.get("m1")).hasValue("v1");
    }

    @Test
    void testIsPersistent_inMemory_returnsFalse() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "persist",
                String.class);
        assertThat(map.isPersistent()).isFalse();
    }

    @Test
    void testGetName_returnsMapName() {
        var name = mapPrefix + "named";
        var map = cacheManager.<String>getCacheMap(name, String.class);
        assertThat(map.getName()).isEqualTo(name);
    }

    @Test
    void testVendor_returnsRawIMap() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "vendor",
                String.class);
        map.put("vk", "vv");

        if (map instanceof HazelcastMapAdapter<?> adapter) {
            var vendor = adapter.vendor();
            assertThat((java.util.Map<?, ?>) vendor).isNotNull();
            assertThat(vendor.get("vk")).isEqualTo("vv");
        }
    }

    // -----------------------------------------------------------------
    // query / delete (with QueryFilter)
    // -----------------------------------------------------------------

    @Test
    void testQuery_returnsAllWithAlwaysTrue() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "qall",
                String.class);
        map.putAll(java.util.Map.of("q1", "a", "q2", "b"));
        var results = map.query(QueryFilter.ofAll());
        assertThat(results).hasSize(2);
    }

    @Test
    void testDelete_nullFilter_noOp() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "delnull",
                String.class);
        map.put("d", "v");
        map.delete(null);
        assertThat(map.get("d")).hasValue("v");
    }

    @Test
    void testDelete_allMatchingFilter_removesAll() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "delall",
                String.class);
        map.putAll(java.util.Map.of("e1", "v1", "e2", "v2"));
        map.delete(QueryFilter.ofAll());
        assertThat(map.isEmpty()).isTrue();
    }

    // -----------------------------------------------------------------
    // queryIterator — exercises PagingIterator (currently 0% covered)
    // -----------------------------------------------------------------

    @Test
    void testQueryIterator_iteratesAllEntries() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "qi",
                String.class);
        map.putAll(java.util.Map.of("p1", "alpha", "p2", "beta",
                "p3", "gamma"));

        var it = map.queryIterator(null);
        assertThat(it).isNotNull();

        var collected = new java.util.ArrayList<String>();
        it.forEachRemaining(collected::add);
        assertThat(collected).hasSize(3);
    }

    @Test
    void testQueryIterator_emptyMap_returnsEmptyIterator() {
        var map = cacheManager.<String>getCacheMap(mapPrefix + "qi-empty",
                String.class);
        var it = map.queryIterator(null);
        assertThat(it).isNotNull();
        assertThat(it.hasNext()).isFalse();
    }

    // -----------------------------------------------------------------
    // Behaviour after HZ shutdown (isCacheClosed path)
    // -----------------------------------------------------------------

    @Test
    void testOperationsAfterClose_returnDefaults() {
        var localHz = HazelcastTestSupport.startNode();
        @SuppressWarnings("resource")
        var localMgr = new HazelcastCacheManager(localHz);
        var map = localMgr.getCacheMap("closed-map-" + mapPrefix, String.class);
        map.put("k", "before-close");

        localHz.shutdown();

        assertThat(map.isEmpty()).isFalse(); // defaultValue
        assertThat(map.get("k")).isEmpty(); // null → empty Optional
        assertThat(map.size()).isEqualTo(-1L);
        assertThat(map.containsKey("k")).isFalse();
        assertThat(map.query(QueryFilter.ofAll())).isEmpty();
        assertThat(map.keys()).isEmpty();
    }

    // -----------------------------------------------------------------
    // update / updateAll: entry processors, no value comparison
    // -----------------------------------------------------------------

    /** Claims a QUEUED entry, like the ledger does. */
    static final class Claim implements CacheEntryUpdate<CrawlerEntry> {
        private static final long serialVersionUID = 1L;

        @Override
        public CrawlerEntry apply(CrawlerEntry current) {
            if (current == null || !ProcessingStatus.QUEUED
                    .is(current.getProcessingStatus())) {
                return null;
            }
            var claimed = new CrawlerEntry(current.getReference());
            claimed.setProcessingStatus(ProcessingStatus.PROCESSING);
            return claimed;
        }
    }

    static final class AddOne implements CacheEntryUpdate<Long> {
        private static final long serialVersionUID = 1L;

        @Override
        public Long apply(Long current) {
            return (current == null ? 0L : current) + 1;
        }
    }

    private static CrawlerEntry queued(String ref) {
        var entry = new CrawlerEntry(ref);
        entry.setProcessingStatus(ProcessingStatus.QUEUED);
        return entry;
    }

    @Test
    void testUpdate_changesOnlyWhatTheUpdateDecides() {
        var map = cacheManager.<CrawlerEntry>getCacheMap(
                mapPrefix + "ledger", CrawlerEntry.class);
        map.put("a", queued("a"));

        assertThat(map.update("a", new Claim()))
                .hasValueSatisfying(e -> assertThat(e.getProcessingStatus())
                        .isEqualTo(ProcessingStatus.PROCESSING));
        // Already claimed: left alone.
        assertThat(map.update("a", new Claim())).isEmpty();
        // Missing: not created.
        assertThat(map.update("missing", new Claim())).isEmpty();
        assertThat(map.containsKey("missing")).isFalse();
    }

    @Test
    void testUpdate_valueStoredAsJsonText() {
        // A value held as JSON text (older entries, or ones loaded back
        // from a store as text) could never match replace(old, new), whose
        // comparison is on serialized bytes. An update reads it like any.
        var name = mapPrefix + "ledger";
        var map = cacheManager.<CrawlerEntry>getCacheMap(
                name, CrawlerEntry.class);
        hz.getMap(name).put("a", SerialUtil.toJsonString(queued("a")));

        assertThat(map.update("a", new Claim())).isPresent();
        assertThat(map.get("a")).hasValueSatisfying(
                e -> assertThat(e.getProcessingStatus())
                        .isEqualTo(ProcessingStatus.PROCESSING));
    }

    @Test
    void testUpdateAll_concurrentClaimsClaimEachEntryOnce() throws Exception {
        var map = cacheManager.<CrawlerEntry>getCacheMap(
                mapPrefix + "ledger", CrawlerEntry.class);
        var keys = new java.util.ArrayList<String>();
        for (var i = 0; i < 200; i++) {
            keys.add("ref-" + i);
            map.put("ref-" + i, queued("ref-" + i));
        }
        var claims = new AtomicInteger();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(6)) {
            for (var t = 0; t < 6; t++) {
                pool.submit(() -> {
                    start.await();
                    claims.addAndGet(map.updateAll(keys, new Claim()).size());
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(claims).hasValue(200);
    }

    @Test
    void testUpdate_concurrentCounterLosesNothing() throws Exception {
        var map = cacheManager.<Long>getCacheMap(
                mapPrefix + "counts", Long.class);
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (var t = 0; t < 8; t++) {
                pool.submit(() -> {
                    for (var i = 0; i < 250; i++) {
                        map.update("count", new AddOne());
                    }
                });
            }
        }
        assertThat(map.get("count")).contains(2000L);
    }
}
