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
package com.norconex.crawler.core.cluster.impl.hazelcast.pipeline;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.ArrayUtils;

import com.norconex.crawler.core.cluster.CacheNames;
import com.norconex.crawler.core.cluster.impl.hazelcast.CacheKeys;
import com.norconex.crawler.core.cluster.impl.hazelcast.HazelcastCluster;
import com.norconex.crawler.core.cluster.impl.hazelcast.event.CacheEntryChangeListener;
import com.norconex.crawler.core.cluster.pipeline.Pipeline;
import com.norconex.crawler.core.cluster.pipeline.PipelineManager;
import com.norconex.crawler.core.cluster.pipeline.PipelineProgress;
import com.norconex.crawler.core.cluster.pipeline.PipelineResult;
import com.norconex.crawler.core.cluster.pipeline.PipelineStatus;
import com.norconex.crawler.core.cluster.pipeline.StepRecord;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class HazelcastPipelineManager
        implements PipelineManager, AutoCloseable {

    private final HazelcastCluster cluster;

    private final Map<String, PipelineExecution> pipelineExecutions =
            new ConcurrentHashMap<>();
    private boolean closed;

    @Override
    public CompletableFuture<PipelineResult>
            executePipeline(@NonNull Pipeline pipeline) {

        if (closed) {
            throw new IllegalStateException(
                    ("Cannot execute pipeline %s on a closed pipeline manager "
                            + "on node %s.").formatted(
                                    pipeline.getId(),
                                    cluster.getLocalNode().getNodeName()));
        }

        if (pipelineExecutions.containsKey(pipeline.getId())) {
            throw new IllegalStateException(
                    "Pipeline %s is already executing on node %s.".formatted(
                            pipeline.getId(),
                            cluster.getLocalNode().getNodeName()));
        }

        // Nothing to run (and coordinate): completed, as with other
        // pipeline managers.
        if (pipeline.getSteps().isEmpty()) {
            var now = System.currentTimeMillis();
            return CompletableFuture.completedFuture(PipelineResult.builder()
                    .pipelineId(pipeline.getId())
                    .status(PipelineStatus.COMPLETED)
                    .startedAt(now)
                    .finishedAt(now)
                    .build());
        }

        // Create execution without try-with-resources so it is not closed
        // immediately. We close it when the returned future completes.
        var exec = new PipelineExecution(cluster, pipeline);
        pipelineExecutions.put(pipeline.getId(), exec);
        var future = exec.execute();
        future.whenComplete((r, e) -> {
            try {
                exec.close();
            } catch (Exception ex) { // ignore close issues, just log
                LOG.debug("Error closing PipelineExecution for {}: {}",
                        pipeline.getId(), ex.toString());
            } finally {
                pipelineExecutions.remove(pipeline.getId());
            }
        });
        return future;
    }

    // invoked by cluster StopController listener
    public void stop() {
        pipelineExecutions.keySet().forEach(this::stopPipeline);
    }

    @Override
    public CompletableFuture<Void> stopPipeline(String pipelineId) {

        var pipeExec = pipelineExecutions.get(pipelineId);
        if (pipeExec != null) {
            LOG.info("Closing pipeline {}...", pipelineId);
            return pipeExec.stopPipeline();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public PipelineProgress getPipelineProgress(String pipelineId) {
        var exec = pipelineExecutions.get(pipelineId);
        if (exec == null) {
            return PipelineProgress.builder()
                    .status(PipelineStatus.PENDING)
                    .build();
        }
        var pipeline = exec.getPipeline();
        var key = CacheKeys.pipelineKey(cluster, pipeline);
        var activeStepOpt =
                cluster.getCacheManager().getPipelineStepCache().get(key);
        var b = PipelineProgress.builder()
                .status(PipelineStatus.PENDING)
                .stepCount(pipeline.getSteps().size());
        activeStepOpt.ifPresent(stepRec -> {
            b.currentStepId(stepRec.getStepId())
                    .status(stepRec.getStatus())
                    .currentStepIndex(ArrayUtils.indexOf(
                            pipeline.getSteps().keySet().toArray(),
                            stepRec.getStepId()));
            var stepProg = pipeline.getStep(stepRec.getStepId()).getProgress();
            if (stepProg != null) {
                b.stepProgress(stepProg.getProgress())
                        .stepMessage(stepProg.getMessage());
            }
        });
        return b.build();
    }

    @Override
    public void close() {
        closed = true;
        pipelineExecutions.clear();
    }

    //--- Hazelcast specific --------------------------------------------------
    /**
     * Adds a listener that will be triggered with the current step when
     * it changes, regardless of which pipeline it is (most would want to filter
     * on pipeline id). Changes to the current step made prior adding this
     * listener are not passed.
     * @param listener the listener to add
     */
    public void addStepChangeListener(
            CacheEntryChangeListener<StepRecord> listener) {
        cluster.getCacheManager().addCacheEntryChangeListener(
                listener, CacheNames.PIPE_CURRENT_STEP);
    }

    public void removeStepChangeListener(
            CacheEntryChangeListener<StepRecord> listener) {
        cluster.getCacheManager().removeCacheEntryChangeListener(
                listener, CacheNames.PIPE_CURRENT_STEP);
    }

    /**
     * Adds a listener that will be triggered with a worker latest status,
     * whenever it changes for any active workers. Changes to worker statuses
     * made prior adding this listener are not passed.
     * @param listener the listener to add
     */
    public void addWorkerStatusListener(
            CacheEntryChangeListener<StepRecord> listener) {
        cluster.getCacheManager().addCacheEntryChangeListener(
                listener, CacheNames.PIPE_WORKER_STATUSES);
    }

    public void removeWorkerStatusListener(
            CacheEntryChangeListener<StepRecord> listener) {
        cluster.getCacheManager().removeCacheEntryChangeListener(
                listener, CacheNames.PIPE_WORKER_STATUSES);
    }
}
