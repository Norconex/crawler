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
package com.norconex.crawler.core.cluster.impl.hazelcast.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import com.norconex.crawler.core.cluster.CacheMap;
import com.norconex.crawler.core.cluster.impl.hazelcast.*;
import com.norconex.crawler.core.cluster.pipeline.*;

class PipelineWorkerHeartbeatTest {
    @SuppressWarnings("unchecked")
    @Test
    void completedWorkerSurvivesCoordinatorHeartbeat() throws Exception {
        var cluster = mock(HazelcastCluster.class, RETURNS_DEEP_STUBS);
        var pipeline = mock(Pipeline.class);
        when(pipeline.getId()).thenReturn("diagnostic");
        when(cluster.getCrawlSession().getCrawlRunId())
                .thenReturn("diagnostic-run");
        when(cluster.getConfiguration().getWorkerHeartbeatInterval())
                .thenReturn(Duration.ofSeconds(1));
        when(cluster.getConfiguration().getNodeExpiryTimeout())
                .thenReturn(Duration.ofSeconds(300));
        CacheMap<StepRecord> pipeCache = mock(CacheMap.class),
                workerCache = mock(CacheMap.class);
        when(cluster.getCacheManager().getPipelineStepCache())
                .thenReturn(pipeCache);
        when(cluster.getCacheManager().getPipelineWorkerStatusCache())
                .thenReturn(workerCache);
        long now = System.currentTimeMillis();
        var coordinator = new StepRecord().setPipelineId("diagnostic")
                .setRunId("diagnostic-run")
                .setStepId("crawlDocuments").setStatus(PipelineStatus.RUNNING)
                .setUpdatedAt(now);
        when(pipeCache.get("pipeline-key"))
                .thenReturn(Optional.of(coordinator));
        var recorded = new AtomicReference<StepRecord>();
        doAnswer(inv -> {
            recorded.set(inv.getArgument(1));
            return null;
        }).when(workerCache).put(anyString(), any());
        var scheduler = mock(ScheduledExecutorService.class);
        var heartbeat = new AtomicReference<Runnable>();
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(),
                anyLong(), any(TimeUnit.class)))
                        .thenAnswer(inv -> {
                            heartbeat.set(inv.getArgument(0));
                            return mock(ScheduledFuture.class);
                        });
        when(scheduler.awaitTermination(anyLong(), any())).thenReturn(true);
        when(scheduler.isTerminated()).thenReturn(true);
        var factory = Executors.defaultThreadFactory();
        try (var utilities = mockStatic(HazelcastUtil.class);
                var keys = mockStatic(CacheKeys.class);
                var executors = mockStatic(Executors.class)) {
            utilities.when(() -> HazelcastUtil.isClusterRunning(cluster))
                    .thenReturn(true);
            keys.when(() -> CacheKeys.pipelineKey(cluster, pipeline))
                    .thenReturn("pipeline-key");
            keys.when(() -> CacheKeys.pipelineWorkerNodeKey(cluster, pipeline))
                    .thenReturn("worker-key");
            executors.when(Executors::defaultThreadFactory).thenReturn(factory);
            executors.when(() -> Executors
                    .newSingleThreadScheduledExecutor(any(ThreadFactory.class)))
                    .thenReturn(scheduler);
            var startedStep = new AtomicReference<StepRecord>();
            try (var state =
                    new PipelineWorkerState(cluster, pipeline,
                            startedStep::set)) {
                state.getCurrentStepRecord().setStepId("crawlDocuments")
                        .setStatus(PipelineStatus.RUNNING).setUpdatedAt(now);
                state.pushWorkerStatus(PipelineStatus.COMPLETED);
                assertEquals(PipelineStatus.COMPLETED,
                        recorded.get().getStatus(),
                        "Worker completion was not published");
                for (int tick = 1; tick <= 30; tick++) {
                    heartbeat.get().run();
                    assertEquals(PipelineStatus.COMPLETED,
                            recorded.get().getStatus(),
                            "Heartbeat " + tick + " lost worker completion");
                }
                assertNull(startedStep.get(), "Completed step restarted");

                coordinator.setStatus(PipelineStatus.COMPLETED)
                        .setUpdatedAt(now + 1);
                heartbeat.get().run();
                assertEquals(PipelineStatus.COMPLETED,
                        recorded.get().getStatus());

                coordinator.setStepId("nextStep")
                        .setStatus(PipelineStatus.RUNNING)
                        .setUpdatedAt(now + 2);
                heartbeat.get().run();
                assertEquals("nextStep", recorded.get().getStepId());
                assertEquals(PipelineStatus.RUNNING,
                        recorded.get().getStatus());
                assertEquals("nextStep", startedStep.get().getStepId());
            }
        }
    }

}
