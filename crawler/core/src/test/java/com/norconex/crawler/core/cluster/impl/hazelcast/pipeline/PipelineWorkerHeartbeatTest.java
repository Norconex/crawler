package com.norconex.crawler.core.cluster.impl.hazelcast.pipeline;

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
            try (var state =
                    new PipelineWorkerState(cluster, pipeline, ignored -> {
                        throw new AssertionError("Step unexpectedly restarted");
                    })) {
                state.getCurrentStepRecord().setStepId("crawlDocuments")
                        .setStatus(PipelineStatus.RUNNING).setUpdatedAt(now);
                state.pushWorkerStatus(PipelineStatus.COMPLETED);
                check(recorded.get().getStatus() == PipelineStatus.COMPLETED,
                        "Worker completion was not published");
                for (int tick = 1; tick <= 30; tick++) {
                    heartbeat.get().run();
                    check(recorded.get()
                            .getStatus() == PipelineStatus.COMPLETED,
                            "Heartbeat " + tick + " lost worker completion");
                }
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }
}
