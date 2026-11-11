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
package com.norconex.crawler.core.cluster.impl.memory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.norconex.crawler.core.cluster.impl.hazelcast.event.CacheEntryChangeListener;
import com.norconex.crawler.core.cluster.pipeline.Pipeline;
import com.norconex.crawler.core.cluster.pipeline.PipelineManager;
import com.norconex.crawler.core.cluster.pipeline.PipelineProgress;
import com.norconex.crawler.core.cluster.pipeline.PipelineResult;
import com.norconex.crawler.core.cluster.pipeline.PipelineStatus;
import com.norconex.crawler.core.cluster.pipeline.Step;
import com.norconex.crawler.core.cluster.pipeline.StepRecord;
import com.norconex.crawler.core.session.CrawlerSession;

import lombok.extern.slf4j.Slf4j;

/**
 * Single-node pipeline manager that executes steps sequentially on the
 * calling thread. No distributed coordination overhead.
 *
 * <p>Used by both the in-memory and MVStore cluster implementations
 * since both are single-node.</p>
 */
@Slf4j
public class LocalPipelineManager implements PipelineManager {

    private final CrawlerSession session;
    private volatile boolean stopRequested;
    private final AtomicReference<Step> currentStep =
            new AtomicReference<>();
    private volatile String currentStepId;
    private final AtomicInteger currentStepIndex = new AtomicInteger();
    private volatile int stepCount;

    public LocalPipelineManager(CrawlerSession session) {
        this.session = session;
    }

    @Override
    public CompletableFuture<PipelineResult> executePipeline(
            Pipeline pipeline) {
        var startedAt = System.currentTimeMillis();
        stepCount = pipeline.getSteps().size();
        currentStepIndex.set(0);
        stopRequested = false;

        PipelineStatus finalStatus = PipelineStatus.COMPLETED;
        String lastStepId = null;

        try {
            for (var stepId : pipeline.getSteps().keySet()) {
                if (stopRequested) {
                    finalStatus = PipelineStatus.STOPPED;
                    break;
                }
                var step = pipeline.getStep(stepId);
                currentStep.set(step);
                currentStepId = stepId;
                lastStepId = stepId;

                LOG.debug("Executing step: {}", stepId);
                step.execute(session);
                currentStep.set(null);
                currentStepIndex.incrementAndGet();

                if (step.isStopRequested()) {
                    finalStatus = PipelineStatus.STOPPED;
                    break;
                }
            }
        } catch (Exception e) {
            LOG.error("Pipeline step '{}' failed.", lastStepId, e);
            finalStatus = PipelineStatus.FAILED;
        }

        var result = PipelineResult.builder()
                .pipelineId(pipeline.getId())
                .status(finalStatus)
                .lastStepId(lastStepId)
                .startedAt(startedAt)
                .finishedAt(System.currentTimeMillis())
                .resumed(false)
                .timedOut(false)
                .build();
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Void> stopPipeline(String pipelineId) {
        stop();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public PipelineProgress getPipelineProgress(String pipelineId) {
        return PipelineProgress.builder()
                .status(stopRequested
                        ? PipelineStatus.STOPPING
                        : PipelineStatus.RUNNING)
                .currentStepId(currentStepId)
                .currentStepIndex(currentStepIndex.get())
                .stepCount(stepCount)
                .build();
    }

    @Override
    public void stop() {
        stopRequested = true;
        var step = currentStep.get();
        if (step != null) {
            step.stop(session);
        }
    }

    @Override
    public void addStepChangeListener(
            CacheEntryChangeListener<StepRecord> listener) {
        // No-op: local execution has no distributed events.
    }

    @Override
    public void removeStepChangeListener(
            CacheEntryChangeListener<StepRecord> listener) {
        // No-op
    }

    @Override
    public void addWorkerStatusListener(
            CacheEntryChangeListener<StepRecord> listener) {
        // No-op
    }

    @Override
    public void removeWorkerStatusListener(
            CacheEntryChangeListener<StepRecord> listener) {
        // No-op
    }
}
