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

import static java.util.Optional.ofNullable;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;

import com.norconex.crawler.core.cluster.CacheMap;
import com.norconex.crawler.core.cluster.impl.hazelcast.CacheKeys;
import com.norconex.crawler.core.cluster.impl.hazelcast.HazelcastCluster;
import com.norconex.crawler.core.cluster.impl.hazelcast.HazelcastUtil;
import com.norconex.crawler.core.cluster.impl.hazelcast.event.CacheEntryChangeListener;
import com.norconex.crawler.core.cluster.pipeline.Pipeline;
import com.norconex.crawler.core.cluster.pipeline.PipelineStatus;
import com.norconex.crawler.core.cluster.pipeline.Step;
import com.norconex.crawler.core.cluster.pipeline.StepRecord;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.core.util.ConcurrentUtil;

import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PipelineWorkerState implements AutoCloseable {
    private final HazelcastCluster cluster;
    private final Pipeline pipeline;
    private final CrawlerSession session;

    // Cache Keys
    private final String pipelineKey; // sessionId:pipelineId
    private final String workerKey; // sessionId:pipelineId:runId:nodeName

    // Caches
    private final CacheMap<StepRecord> pipelineStepCache;
    private final CacheMap<StepRecord> workerStatusCache;

    // Flags
    @Getter
    private final AtomicBoolean closed = new AtomicBoolean(false);
    @Getter
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    // Current Step
    @Getter
    private final StepRecord currentStepRecord;
    // Worker Status
    private PipelineStatus workerStatus = PipelineStatus.PENDING;
    private String workerStepId;

    private final ScheduledExecutorService statusUpdater =
            Executors.newSingleThreadScheduledExecutor(
                    BasicThreadFactory.builder()
                            .namingPattern("WORKER-STATUS-UPDATER")
                            .daemon(true)
                            .build());
    private ScheduledFuture<?> workerStatusFuture;

    // Misc.
    private final Consumer<StepRecord> onNewStepRun;
    @Getter
    private final long startedAt;
    // Use concurrent set to avoid races when listener fires concurrently
    @Getter
    private final Set<String> encounteredSteps = ConcurrentHashMap.newKeySet();
    // listener reference so we can remove it on close
    private CacheEntryChangeListener<StepRecord> pipelineStepListener;
    private final CompletableFuture<Void> completion =
            new CompletableFuture<>();

    public PipelineWorkerState(
            @NonNull HazelcastCluster cluster,
            @NonNull Pipeline pipeline,
            @NonNull Consumer<StepRecord> onNewStepRun) {
        this.cluster = cluster;
        this.pipeline = pipeline;
        this.onNewStepRun = onNewStepRun;
        session = cluster.getCrawlSession();
        startedAt = System.currentTimeMillis();

        pipelineKey = CacheKeys.pipelineKey(cluster, pipeline);
        workerKey = CacheKeys.pipelineWorkerNodeKey(cluster, pipeline);

        pipelineStepCache = cluster.getCacheManager().getPipelineStepCache();
        workerStatusCache =
                cluster.getCacheManager().getPipelineWorkerStatusCache();

        // no step, no updatedAt yet
        currentStepRecord = new StepRecord()
                .setPipelineId(pipeline.getId())
                .setRunId(session.getCrawlRunId())
                .setStatus(PipelineStatus.PENDING);

        // Determine effective heartbeat interval based on configuration
        var config = cluster.getConfiguration();
        long expiryMs = ofNullable(config.getNodeExpiryTimeout())
                .map(Duration::toMillis)
                .orElse(30_000L);
        long rawHeartbeatMs = ofNullable(config.getWorkerHeartbeatInterval())
                .map(Duration::toMillis)
                .orElse(1_000L);
        var minHeartbeatMs = 500L;
        var maxByExpiry = Math.max(minHeartbeatMs, expiryMs / 3);
        var heartbeatMs = Math.max(minHeartbeatMs,
                Math.min(rawHeartbeatMs, maxByExpiry));

        if (heartbeatMs != rawHeartbeatMs) {
            LOG.info("Adjusting worker heartbeat interval from {} ms to {} ms "
                    + "based on node expiry timeout {} ms.",
                    rawHeartbeatMs, heartbeatMs, expiryMs);
        }

        if (cluster.isStandalone()) {
            workerStatusFuture = null;
            return;
        }

        workerStatusFuture = statusUpdater.scheduleAtFixedRate(() -> {
            if (closed.get()) {
                return;
            }
            // Self-cancel once the cluster is no longer running to
            // avoid a tight loop of no-op heartbeats that spam logs
            // and waste CPU after shutdown.
            if (!HazelcastUtil.isClusterRunning(cluster)) {
                LOG.debug("Cancelling worker status heartbeat for "
                        + "pipeline {} because cluster is not running.",
                        pipeline.getId());
                if (workerStatusFuture != null) {
                    workerStatusFuture.cancel(false);
                }
                return;
            }

            // Keep the worker's view of the active step in sync with the
            // coordinator. This is especially important for nodes joining
            // mid-run (e.g., resume) where the Hazelcast cache event can be
            // missed.
            var pipeRec = pipelineStepCache.get(pipelineKey).orElse(null);
            if (pipeRec != null && isStepForThisRun(pipeRec)) {
                var curStepId = currentStepRecord.getStepId();
                var curStatus = currentStepRecord.getStatus();
                var curUpdatedAt = currentStepRecord.getUpdatedAt();
                var needsSync = curStepId == null
                        || curStatus == null
                        || curUpdatedAt < pipeRec.getUpdatedAt()
                        || !pipeRec.getStatus().equals(curStatus)
                        || (pipeRec.getStepId() != null
                                && !pipeRec.getStepId().equals(curStepId));
                if (needsSync) {
                    updateCurrentStepRecord(currentRec -> {
                        logStepRecordReceived(pipeRec, "heartbeat sync");
                        currentRec.setStatus(pipeRec.getStatus())
                                .setStepId(pipeRec.getStepId())
                                .setUpdatedAt(pipeRec.getUpdatedAt());
                    });
                }
            }

            refreshWorkerStatus();
        }, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS); // Changed initial delay from 0 to heartbeatMs
    }

    /**
     * Registers the step listener. Must be invoked after the owning
     * PipelineWorker "state" field is set to avoid early callbacks with a
     * null worker state.
     */
    public void registerStepListener() {
        if (closed.get()) {
            return; // nothing to do
        }
        if (pipelineStepListener == null) {
            initStepListener();
        }

        // Sync current step immediately in case this node joined after the
        // coordinator already published the active step (common on resume).
        // This avoids waiting for the periodic explicit-check.
        updateCurrentStepRecord(currentRec -> {
            var rec = pipelineStepCache.get(pipelineKey).orElse(null);
            logStepRecordReceived(rec, "initial sync");
            if (rec == null || !isStepForThisRun(rec)) {
                currentRec.setStatus(PipelineStatus.PENDING)
                        .setStepId(null)
                        .setUpdatedAt(0);
            } else {
                currentRec.setStatus(rec.getStatus())
                        .setStepId(rec.getStepId())
                        .setUpdatedAt(rec.getUpdatedAt());
            }
        });
    }

    public Step getCurrentStep() {
        return pipeline.getStep(currentStepRecord.getStepId());
    }

    public synchronized void pushWorkerStatus(PipelineStatus status) {
        workerStatus = status;
        synchronized (currentStepRecord) {
            workerStepId = currentStepRecord.getStepId();
        }
        refreshWorkerStatus();
    }

    private synchronized void syncWorkerStatus(String stepId,
            PipelineStatus status) {
        if (!Objects.equals(workerStepId, stepId)
                || !workerStatus.isTerminal()) {
            workerStatus = status;
            workerStepId = stepId;
        }
        refreshWorkerStatus();
    }

    private synchronized void refreshWorkerStatus() {
        if (!HazelcastUtil.isClusterRunning(cluster)) {
            LOG.debug("Cluster reported as not running by node {}",
                    cluster.getLocalNode().getNodeName());
            return;
        }

        // Note: stepId may be empty when status=PENDING (initial worker
        // announcement). The coordinator will assign the stepId when it
        // writes RUNNING status.
        var rec = new StepRecord()
                .setPipelineId(pipeline.getId())
                .setStepId(workerStepId)
                .setStatus(workerStatus)
                .setUpdatedAt(System.currentTimeMillis())
                .setRunId(session.getCrawlRunId());
        try {
            workerStatusCache.put(workerKey, rec);
        } catch (RuntimeException e) {
            LOG.debug("Worker status update failed for {}: {}",
                    pipeline.getId(), e.toString());
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            LOG.debug("Closing PipelineWorker for pipeline {}",
                    pipeline.getId());
            if (workerStatusFuture != null) {
                workerStatusFuture.cancel(true);
                workerStatusFuture = null;
            }
            // attempt graceful shutdown of scheduler
            ConcurrentUtil.shutdownAndAwait(statusUpdater,
                    Duration.ofSeconds(2));

            if (pipelineStepListener != null) {
                try {
                    cluster.getPipelineManager()
                            .removeStepChangeListener(pipelineStepListener);
                } catch (Exception e) {
                    LOG.debug("Could not remove step listener for "
                            + "pipeline {}: {}",
                            pipeline.getId(), e.toString());
                }
                pipelineStepListener = null;
            }
            LOG.debug("PipelineWorker closed for pipeline {}",
                    pipeline.getId());
            completion.complete(null);
        }
    }

    /**
     * Returns a future that completes when this worker state has fully
     * closed, including listener deregistration and scheduler shutdown.
     *
     * @return completion future
     */
    public CompletableFuture<Void> completion() {
        return completion;
    }

    //--- Private methods ------------------------------------------------------

    private void logStepRecordReceived(StepRecord rec, String source) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("""
                Worker node {} {} for \
                pipeline {} active step got: "{}" -> {}.""",
                    cluster.getLocalNode().getNodeName(),
                    source,
                    pipeline.getId(),
                    rec == null ? null : rec.getStepId(),
                    rec == null ? null : rec.getStatus());
        }
    }

    private void initStepListener() {

        pipelineStepListener = (key, pipeRec) -> {
            logStepRecordReceived(pipeRec, "listener");
            // Only accept updates for this exact run's key
            if (!pipelineKey.equals(key)) {
                return;
            }

            if (isStepForThisRun(pipeRec)) {
                updateCurrentStepRecord(curRec -> {
                    curRec.setStatus(pipeRec.getStatus())
                            .setStepId(pipeRec.getStepId())
                            .setUpdatedAt(pipeRec.getUpdatedAt());
                });
            }
        };
        cluster.getPipelineManager()
                .addStepChangeListener(pipelineStepListener);
    }

    private void updateCurrentStepRecord(Consumer<StepRecord> stepUpdater) {
        StepRecord recordSnapshot;
        boolean shouldRun;
        boolean shouldPushStatus;
        String publishedStepId;
        PipelineStatus publishedStepStatus;

        synchronized (currentStepRecord) {
            var oldStatus = currentStepRecord.getStatus();
            var oldStepId = currentStepRecord.getStepId();
            stepUpdater.accept(currentStepRecord);
            var newStatus = currentStepRecord.getStatus();
            var newStepId = currentStepRecord.getStepId();
            publishedStepId = newStepId;
            publishedStepStatus = newStatus;

            // if newly set to run, run it.
            // if already running, run it if step id has changed
            var stepChanged = (oldStepId == null && newStepId != null)
                    || (oldStepId != null && !oldStepId.equals(newStepId));

            // Reject status downgrade: don't overwrite a terminal status
            // with a non-terminal one for the same step. This prevents a
            // race where a new coordinator re-pushes RUNNING for a step
            // this worker already COMPLETED, which would deadlock.
            if (!stepChanged
                    && oldStatus != null && oldStatus.isTerminal()
                    && newStatus != null && !newStatus.isTerminal()) {
                LOG.debug(
                        "Rejecting status downgrade for step {}: "
                                + "{} -> {}",
                        newStepId, oldStatus, newStatus);
                currentStepRecord.setStatus(oldStatus);
                return;
            }

            shouldRun = newStatus != null && newStatus.isRunning()
                    && (((oldStatus == null) || !oldStatus.isRunning())
                            || stepChanged);

            // Push status immediately when stepId changes or status changes
            shouldPushStatus = stepChanged
                    || (oldStatus != newStatus);

            if (shouldRun) {
                LOG.info("{}:{} -> {}:{}",
                        oldStepId, oldStatus, newStepId, newStatus);
                // Create snapshot for callback outside synchronized block
                recordSnapshot = new StepRecord()
                        .setPipelineId(currentStepRecord.getPipelineId())
                        .setRunId(currentStepRecord.getRunId())
                        .setStepId(currentStepRecord.getStepId())
                        .setStatus(currentStepRecord.getStatus())
                        .setUpdatedAt(currentStepRecord.getUpdatedAt());
            } else {
                LOG.debug("{}:{} -> {}:{}",
                        oldStepId, oldStatus, newStepId, newStatus);
                recordSnapshot = null;
            }
        }

        // Push status immediately to ensure coordinator sees it
        if (shouldPushStatus) {
            syncWorkerStatus(publishedStepId, publishedStepStatus);
        }

        // Execute callback OUTSIDE synchronized block to avoid deadlocks
        if (shouldRun && recordSnapshot != null) {
            onNewStepRun.accept(recordSnapshot);
        }
    }

    private boolean isStepForThisRun(StepRecord pipeStepRec) {
        // Ignore records for other runs within the same session
        if (!session.getCrawlRunId().equals(pipeStepRec.getRunId())) {
            LOG.debug("Ignoring step update for other runId {} (current {}).",
                    pipeStepRec.getRunId(), session.getCrawlRunId());
            return false;
        }
        return true;
    }
}
