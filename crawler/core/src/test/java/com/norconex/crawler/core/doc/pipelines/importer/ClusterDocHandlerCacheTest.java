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
package com.norconex.crawler.core.doc.pipelines.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.norconex.crawler.core.cluster.support.InMemoryCacheManager;

class ClusterDocHandlerCacheTest {

    @Test
    void testCallsLoaderOnceThenReusesCachedValue() throws IOException {
        var cache = new ClusterDocHandlerCache(new InMemoryCacheManager());
        var loads = new AtomicInteger();

        var first = cache.computeIfAbsent("key", k -> {
            loads.incrementAndGet();
            return "value";
        });
        var second = cache.computeIfAbsent("key", k -> {
            loads.incrementAndGet();
            return "value";
        });

        assertThat(first).isEqualTo("value");
        assertThat(second).isEqualTo("value");
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    void testDifferentKeysEachCallTheLoader() throws IOException {
        var cache = new ClusterDocHandlerCache(new InMemoryCacheManager());
        var loads = new AtomicInteger();

        cache.computeIfAbsent("key-a", k -> {
            loads.incrementAndGet();
            return "value-a";
        });
        cache.computeIfAbsent("key-b", k -> {
            loads.incrementAndGet();
            return "value-b";
        });

        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    void testLoaderIOExceptionPropagatesUnwrapped() {
        var cache = new ClusterDocHandlerCache(new InMemoryCacheManager());

        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> cache.computeIfAbsent("key", k -> {
                    throw new IOException("computation failed");
                }))
                .withMessage("computation failed");
    }

    @Test
    void testBackedByTheCrawlerScopedCacheSoItPersistsAcrossSessions()
            throws IOException {
        var manager = new InMemoryCacheManager();
        var cache = new ClusterDocHandlerCache(manager);

        cache.computeIfAbsent("key", k -> "value");

        assertThat(manager.getCrawlerCache().get("key")).contains("value");
    }
}
