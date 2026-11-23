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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.reflect.MethodUtils;
import org.junit.jupiter.api.Test;

import com.norconex.crawler.core.cluster.CacheMap;
import com.norconex.crawler.core.cluster.impl.hazelcast.CacheKeys;
import com.norconex.crawler.core.cluster.impl.hazelcast.HazelcastCluster;
import com.norconex.crawler.core.cluster.impl.hazelcast.HazelcastUtil;
import com.norconex.crawler.core.cluster.pipeline.Pipeline;
import com.norconex.crawler.core.cluster.pipeline.PipelineStatus;
import com.norconex.crawler.core.cluster.pipeline.StepRecord;

class PipelineWorkerHeartbeatTest {

    private static final String RUN = "diagnostic-run";

    @Test
    void completedWorkerSurvivesCoordinatorHeartbeat() throws Exception {
        long now = System.currentTimeMillis();
        var coordinator =
                stepRecord("crawlDocuments", PipelineStatus.RUNNING, now);
        try (var h = new Harness(Optional.of(coordinator))) {
            var state = h.state;
            state.getCurrentStepRecord().setStepId("crawlDocuments")
                    .setStatus(PipelineStatus.RUNNING).setUpdatedAt(now);
            state.pushWorkerStatus(PipelineStatus.COMPLETED);
            assertEquals(PipelineStatus.COMPLETED,
                    h.published.get().getStatus(),
                    "Worker completion was not published");
            for (int tick = 1; tick <= 30; tick++) {
                h.tick();
                assertEquals(PipelineStatus.COMPLETED,
                        h.published.get().getStatus(),
                        "Heartbeat " + tick + " lost worker completion");
            }
            assertEquals(List.of(), h.started, "Completed step restarted");

            coordinator.setStatus(PipelineStatus.COMPLETED)
                    .setUpdatedAt(now + 1);
            h.tick();
            assertEquals(PipelineStatus.COMPLETED,
                    h.published.get().getStatus());

            coordinator.setStepId("nextStep")
                    .setStatus(PipelineStatus.RUNNING)
                    .setUpdatedAt(now + 2);
            h.tick();
            assertEquals("nextStep", h.published.get().getStepId());
            assertEquals(PipelineStatus.RUNNING,
                    h.published.get().getStatus());
            assertEquals(List.of("nextStep"), h.started);
        }
    }

    /**
     * The worker used to reset itself to PENDING (no step) when the step
     * record could not be found. That bought nothing: the coordinator counts
     * a worker reporting another step as PENDING anyway, and with no record
     * there is nothing to sync to. It did cost something: when the record
     * came back with the step the worker was still running, the worker saw
     * a "new" step and started it a second time. Now the worker keeps
     * running and reporting its step until a record says otherwise.
     */
    @Test
    void missingStepRecordNeitherStopsNorRestartsTheStep() throws Exception {
        long now = System.currentTimeMillis();
        var coordinator =
                stepRecord("crawlDocuments", PipelineStatus.RUNNING, now);
        var current = new AtomicReference<Optional<StepRecord>>(
                Optional.of(coordinator));
        try (var h = new Harness(current)) {
            h.tick();
            assertEquals(List.of("crawlDocuments"), h.started);

            // The record disappears (lost, cleared, another run's).
            current.set(Optional.empty());
            for (int tick = 1; tick <= 30; tick++) {
                h.tick();
                assertEquals("crawlDocuments", h.published.get().getStepId());
                assertEquals(PipelineStatus.RUNNING,
                        h.published.get().getStatus());
            }
            current.set(Optional.of(
                    stepRecord("crawlDocuments", PipelineStatus.RUNNING, now)
                            .setRunId("another-run")));
            h.tick();
            assertEquals("crawlDocuments", h.published.get().getStepId());

            // It comes back, same step: still running once, not twice.
            current.set(Optional.of(stepRecord(
                    "crawlDocuments", PipelineStatus.RUNNING, now + 1)));
            h.tick();
            assertEquals(List.of("crawlDocuments"), h.started);

            // A new step is picked up as usual.
            current.set(Optional.of(
                    stepRecord("nextStep", PipelineStatus.RUNNING, now + 2)));
            h.tick();
            assertEquals(List.of("crawlDocuments", "nextStep"), h.started);
            assertEquals("nextStep", h.published.get().getStepId());
        }
    }

    /**
     * The heartbeat and the step listener publish after releasing the step
     * record, so one can publish a snapshot the other has moved past. A
     * late snapshot of the previous step must not replace the current one.
     */
    @Test
    void staleSnapshotDoesNotOverwriteCurrentStep() throws Exception {
        long now = System.currentTimeMillis();
        var coordinator = stepRecord("nextStep", PipelineStatus.RUNNING, now);
        try (var h = new Harness(Optional.of(coordinator))) {
            h.tick();
            assertEquals("nextStep", h.published.get().getStepId());

            // A late publish of the step the record already moved past.
            MethodUtils.invokeMethod(h.state, true, "syncWorkerStatus",
                    new Object[] { "crawlDocuments", PipelineStatus.RUNNING },
                    new Class<?>[] { String.class, PipelineStatus.class });
            assertEquals("nextStep", h.published.get().getStepId());

            h.tick();
            assertEquals("nextStep", h.published.get().getStepId());
            assertEquals(PipelineStatus.RUNNING,
                    h.published.get().getStatus());
        }
    }

    private static StepRecord stepRecord(
            String stepId, PipelineStatus status, long updatedAt) {
        return new StepRecord().setPipelineId("diagnostic").setRunId(RUN)
                .setStepId(stepId).setStatus(status).setUpdatedAt(updatedAt);
    }

    /**
     * A worker state whose heartbeat is run by hand, reading the step
     * record from {@code stepRecord} and publishing to {@link #published}.
     */
    private static final class Harness implements AutoCloseable {
        private final AtomicReference<StepRecord> published =
                new AtomicReference<>();
        private final List<String> started = new ArrayList<>();
        private final AtomicReference<Runnable> heartbeat =
                new AtomicReference<>();
        private final AutoCloseable[] statics;
        private final PipelineWorkerState state;

        Harness(Optional<StepRecord> fixed) throws Exception {
            this(new AtomicReference<>(fixed));
        }

        @SuppressWarnings("unchecked")
        Harness(AtomicReference<Optional<StepRecord>> stepRecord)
                throws Exception {
            var cluster = mock(HazelcastCluster.class, RETURNS_DEEP_STUBS);
            var pipeline = mock(Pipeline.class);
            when(pipeline.getId()).thenReturn("diagnostic");
            when(cluster.getCrawlSession().getCrawlRunId()).thenReturn(RUN);
            when(cluster.getConfiguration().getWorkerHeartbeatInterval())
                    .thenReturn(Duration.ofSeconds(1));
            when(cluster.getConfiguration().getNodeExpiryTimeout())
                    .thenReturn(Duration.ofSeconds(300));
            CacheMap<StepRecord> pipeCache = mock(CacheMap.class);
            CacheMap<StepRecord> workerCache = mock(CacheMap.class);
            when(cluster.getCacheManager().getPipelineStepCache())
                    .thenReturn(pipeCache);
            when(cluster.getCacheManager().getPipelineWorkerStatusCache())
                    .thenReturn(workerCache);
            when(pipeCache.get("pipeline-key"))
                    .thenAnswer(inv -> stepRecord.get());
            doAnswer(inv -> {
                published.set(inv.getArgument(1));
                return null;
            }).when(workerCache).put(anyString(), any());
            var scheduler = mock(ScheduledExecutorService.class);
            when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(),
                    anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
                        heartbeat.set(inv.getArgument(0));
                        return mock(ScheduledFuture.class);
                    });
            when(scheduler.awaitTermination(anyLong(), any())).thenReturn(true);
            when(scheduler.isTerminated()).thenReturn(true);
            var factory = Executors.defaultThreadFactory();

            var utilities = mockStatic(HazelcastUtil.class);
            var keys = mockStatic(CacheKeys.class);
            var executors = mockStatic(Executors.class);
            statics = new AutoCloseable[] { executors, keys, utilities };
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
            state = new PipelineWorkerState(cluster, pipeline,
                    rec -> started.add(rec.getStepId()));
            assertNull(published.get());
        }

        void tick() {
            heartbeat.get().run();
        }

        @Override
        public void close() throws Exception {
            try {
                state.close();
            } finally {
                for (var s : statics) {
                    s.close();
                }
            }
        }
    }
}
