/* Copyright 2026 Norconex Inc.
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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.h2.mvstore.MVStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.crawler.core.cluster.QueryFilter;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

class MVStoreCacheMapTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private String ref;
        private String status;
    }

    @TempDir
    Path tempDir;

    private MVStore store;
    private MVStoreCacheMap<Item> cache;

    @BeforeEach
    void beforeEach() {
        store = new MVStore.Builder()
                .fileName(tempDir.resolve("test.db").toString())
                .autoCompactFillRate(90)
                .open();
        cache = new MVStoreCacheMap<>(
                store.<String, String>openMap("map.test"),
                Item.class, "test", true);
    }

    @AfterEach
    void afterEach() {
        store.close();
    }

    private static QueryFilter status(String status) {
        return QueryFilter.of("status", status);
    }

    private long scanCount(String status) {
        return cache.query(status(status)).size();
    }

    @Test
    void testCountFollowsEveryKindOfWrite() {
        cache.put("a", new Item("a", "QUEUED"));
        cache.put("b", new Item("b", "QUEUED"));
        // first count builds the index from existing data
        assertThat(cache.count(status("QUEUED"))).isEqualTo(2);

        cache.put("a", new Item("a", "PROCESSING"));
        cache.putIfAbsent("c", new Item("c", "QUEUED"));
        cache.compute("b", (k, v) -> new Item("b", "PROCESSED"));
        cache.merge("d", new Item("d", "QUEUED"), (o, n) -> n);
        cache.computeIfAbsent("e", k -> new Item("e", "QUEUED"));
        cache.replace("c", cache.get("c").orElseThrow(),
                new Item("c", "PROCESSING"));
        cache.remove("e");

        assertThat(cache.count(status("QUEUED"))).isEqualTo(1);
        assertThat(cache.count(status("PROCESSING"))).isEqualTo(2);
        assertThat(cache.count(status("PROCESSED"))).isEqualTo(1);
        assertThat(cache.count(status("NONE"))).isZero();
        assertThat(cache.count(QueryFilter.ofAll())).isEqualTo(4);

        cache.delete(status("PROCESSING"));
        assertThat(cache.count(status("PROCESSING"))).isZero();
        assertThat(cache.size()).isEqualTo(2);

        cache.clear();
        assertThat(cache.count(status("QUEUED"))).isZero();
        cache.put("z", new Item("z", "QUEUED"));
        assertThat(cache.count(status("QUEUED"))).isEqualTo(1);
    }

    @Test
    void testCountMatchesScanUnderConcurrentWrites() throws Exception {
        for (var i = 0; i < 500; i++) {
            cache.put("k" + i, new Item("k" + i, "QUEUED"));
        }
        var states = new String[] { "QUEUED", "PROCESSING", "PROCESSED" };
        var pool = Executors.newFixedThreadPool(6);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            // Writers racing on overlapping keys while another thread
            // triggers index creation and repeatedly counts.
            for (var t = 0; t < 4; t++) {
                var seed = t;
                futures.add(pool.submit(() -> {
                    for (var i = 0; i < 4000; i++) {
                        var key = "k" + ((i * 7 + seed) % 700);
                        if (i % 11 == 0) {
                            cache.remove(key);
                        } else {
                            cache.put(key, new Item(key,
                                    states[(i + seed) % states.length]));
                        }
                    }
                }));
            }
            futures.add(pool.submit(() -> {
                for (var i = 0; i < 300; i++) {
                    cache.count(status("QUEUED"));
                    cache.count(status("PROCESSED"));
                }
            }));
            for (var f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        for (var s : states) {
            assertThat(cache.count(status(s))).as(s).isEqualTo(scanCount(s));
        }
        assertThat(cache.count(status("QUEUED"))
                + cache.count(status("PROCESSING"))
                + cache.count(status("PROCESSED")))
                        .isEqualTo(cache.size());
    }

    @Test
    void testScansSurviveConcurrentCommitsAndCompaction() throws Exception {
        // Tiny page cache and no version retention: pages must be re-read
        // from the file, where compaction may already have freed the chunk.
        store.close();
        store = new MVStore.Builder()
                .fileName(tempDir.resolve("churn.db").toString())
                .cacheSize(1)
                .autoCompactFillRate(90)
                .open();
        store.setRetentionTime(0);
        cache = new MVStoreCacheMap<>(
                store.<String, String>openMap("map.churn"),
                Item.class, "churn", true);

        var pad = "x".repeat(400);
        var total = 20_000;
        for (var i = 0; i < total; i++) {
            cache.put("k" + i, new Item(pad + i, "QUEUED"));
        }
        store.commit();
        var running = new java.util.concurrent.atomic.AtomicBoolean(true);
        var pool = Executors.newFixedThreadPool(2);
        try {
            // Churn: rewrite values, commit, and compact aggressively.
            var churn = pool.submit(() -> {
                var n = 0;
                while (running.get()) {
                    for (var i = 0; i < 500; i++) {
                        cache.put("k" + ((n * 500 + i) % total),
                                new Item(pad + n,
                                        n % 2 == 0 ? "QUEUED" : "DONE"));
                    }
                    store.commit();
                    store.compact(90, 256 * 1024);
                    n++;
                }
            });
            for (var i = 0; i < 15; i++) {
                var seen = new int[1];
                cache.forEach((k, v) -> {
                    if (++seen[0] % 200 == 0) {
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
                assertThat(seen[0]).isEqualTo(total);
            }
            running.set(false);
            churn.get(30, TimeUnit.SECONDS);
        } finally {
            running.set(false);
            pool.shutdownNow();
        }
    }

    @Test
    void testTimingIndexedVersusScan() {
        for (var i = 0; i < 30_000; i++) {
            cache.put("k" + i,
                    new Item("k" + i, i % 3 == 0 ? "QUEUED" : "PROCESSED"));
        }
        cache.count(status("QUEUED")); // build the index

        var t0 = System.nanoTime();
        for (var i = 0; i < 100; i++) {
            cache.count(status("QUEUED"));
        }
        var indexedMs = (System.nanoTime() - t0) / 1_000_000;

        t0 = System.nanoTime();
        for (var i = 0; i < 5; i++) {
            scanCount("QUEUED");
        }
        var scanMs = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf(
                "TIMING 30k entries: 100 indexed counts=%d ms, "
                        + "5 scan counts=%d ms%n",
                indexedMs, scanMs);
        assertThat(cache.count(status("QUEUED"))).isEqualTo(10_000);
    }
}
