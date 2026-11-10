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
package com.norconex.crawler.core.cluster.impl.hazelcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.crawler.core.junit.WithLogLevel;
import com.norconex.crawler.core.cluster.CacheManager;
import com.norconex.crawler.core.cluster.CacheMap;
import com.norconex.crawler.core.cluster.pipeline.PipelineManager;
import com.norconex.crawler.core.cluster.support.InMemoryCacheMap;

@Timeout(30)
class CacheStopControllerTest {

    @Test
    void init_triggersImmediateStopWhenSignalAlreadyPresent() {
        var adminCache = new InMemoryCacheMap<String>("admin");
        adminCache.put("STOP", "1");
        var fixture = fixture(adminCache);
        var controller = new CacheStopController(fixture.cluster);

        try {
            controller.init();

            verify(fixture.pipelineManager).stop();
        } finally {
            controller.close();
        }
    }

    @Test
    void sendClusterStopSignal_persistsStopMarker() {
        var adminCache = new InMemoryCacheMap<String>("admin");
        var fixture = fixture(adminCache);
        var controller = new CacheStopController(fixture.cluster);

        try {
            controller.sendClusterStopSignal();

            assertThat(adminCache.get("STOP")).contains("1");
        } finally {
            controller.close();
        }
    }

    @Test
    void init_pollsAndTriggersStopAfterSignalIsSent() {
        var adminCache = new InMemoryCacheMap<String>("admin");
        var fixture = fixture(adminCache);
        var controller = new CacheStopController(fixture.cluster);

        try {
            controller.init();
            controller.sendClusterStopSignal();

            verify(fixture.pipelineManager, timeout(2500)).stop();
        } finally {
            controller.close();
        }
    }

    @Test
    void init_onlyStopsOnceWhenPollingSeesRepeatedStopSignal() {
        var adminCache = new InMemoryCacheMap<String>("admin");
        var fixture = fixture(adminCache);
        var controller = new CacheStopController(fixture.cluster);

        try {
            controller.init();
            controller.sendClusterStopSignal();

            verify(fixture.pipelineManager, timeout(2500)).stop();
            Thread.sleep(1200);
            verify(fixture.pipelineManager).stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            controller.close();
        }
    }

    @Test
    @WithLogLevel(value = "OFF", classes = CacheStopController.class)
    void init_keepsPollingWhenAdminCacheCheckThrows() {
        @SuppressWarnings("unchecked")
        CacheMap<String> adminCache = mock(CacheMap.class);
        var fixture = fixture(adminCache);
        var controller = new CacheStopController(fixture.cluster);

        when(adminCache.containsKey("STOP"))
                .thenReturn(false)
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(false);

        try {
            controller.init();

            Thread.sleep(2200);

            verify(adminCache, timeout(2500).atLeast(3)).containsKey("STOP");
            verify(fixture.pipelineManager, never()).stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            controller.close();
        }
    }

    @Test
    void sendClusterStopSignal_doesNotOverwriteExistingMarker() {
        @SuppressWarnings("unchecked")
        CacheMap<String> adminCache = mock(CacheMap.class);
        var fixture = fixture(adminCache);
        var controller = new CacheStopController(fixture.cluster);

        try {
            controller.sendClusterStopSignal();

            verify(adminCache).putIfAbsent("STOP", "1");
            verify(adminCache, never()).put(anyString(), anyString());
        } finally {
            controller.close();
        }
    }

    private Fixture fixture(CacheMap<String> adminCache) {
        var cluster = mock(HazelcastCluster.class);
        var cacheManager = mock(CacheManager.class);
        var pipelineManager = mock(PipelineManager.class);

        when(cluster.getCacheManager()).thenReturn(cacheManager);
        when(cluster.getPipelineManager()).thenReturn(pipelineManager);
        when(cacheManager.getAdminCache()).thenReturn(adminCache);

        return new Fixture(cluster, pipelineManager);
    }

    private record Fixture(
            HazelcastCluster cluster,
            PipelineManager pipelineManager) {
    }
}
