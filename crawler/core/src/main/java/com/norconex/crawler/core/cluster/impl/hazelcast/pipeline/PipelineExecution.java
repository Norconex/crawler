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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.norconex.crawler.core.cluster.impl.hazelcast.HazelcastCluster;
import com.norconex.crawler.core.cluster.impl.hazelcast.event.CoordinatorChangeListener;
import com.norconex.crawler.core.cluster.pipeline.Pipeline;
import com.norconex.crawler.core.cluster.pipeline.PipelineException;
import com.norconex.crawler.core.cluster.pipeline.PipelineResult;
import com.norconex.crawler.core.cluster.pipeline.PipelineStatus;
import com.norconex.crawler.core.util.ExceptionSwallower;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Execute a worker and, if applicable, a coordinator to execute a pipeline.
 * This class is scoped to a single pipeline execution and not meant to be
 * reused.
 */
@Slf4j
public class PipelineExecution implements AutoCloseable {

    private static final long SHUTDOWN_AWAIT_SECONDS = 5;

    private final HazelcastCluster cluster;
    @Getter(value = AccessLevel.PACKAGE)
    private final Pipeline pipeline;

    private PipelineCoordinator coordinator;
    private PipelineWorker worker;
    private CompletableFuture<PipelineResult> workerFuture;
    private CompletableFuture<Void> coordinatorFuture;
    private CompletableFuture<Void> workerShutdownFuture;

    private boolean executed;

    private final CompletableFuture<PipelineResult> resultFuture =
            new CompletableFuture<>();

    private final ExecutorService executor =
            Executors.newCachedThreadPool(createThreadFactory());
    private final CoordinatorChangeListener coordChangeListener;
    private final boolean coordinatorListenerRegistered;

    private boolean isCoordinator;

    public PipelineExecution(
            HazelcastCluster cluster, Pipeline pipeline) {
        this.cluster = cluster;
        this.pipeline = pipeline;
        coordChangeListener = this::handleCoordinatorChange;
        coordinatorListenerRegistered = cluster.isClustered();
        if (coordinatorListenerRegistered) {
            cluster.addCoordinatorChangeListener(coordChangeListener);
        }
    }

    public CompletableFuture<PipelineResult> execute() {
        if (executed) {
            throw new IllegalStateException(
                    "Pipeline %s already executed or is executing.");
        }
        executed = true;

        // Check if we're the coordinator
        isCoordinator = cluster.getLocalNode().isCoordinator();
        logMode(isCoordinator ? "COORDINATOR" : "worker");
        if (isCoordinator) {
            startCoordinator();
        }

        // Always start workers - they wait for coordinator to assign work
        return startWorker();
    }

    private CompletableFuture<PipelineResult> startWorker() {
        LOG.info("Starting pipeline worker for {} on node {}",
                pipeline.getId(), cluster.getLocalNode().getNodeName());
        worker = new PipelineWorker(cluster, pipeline);
        workerFuture = CompletableFuture.supplyAsync(worker::start, executor)
                .exceptionally(ex -> {
                    ExceptionSwallower.close(worker);
                    // Should not happen(?) given the worker should mark
                    // itself "failed" if exception.
                    throw new PipelineException(
                            "Pipeline %s worker threw an exception it was "
                                    + "not able to handle for node %s.",
                            ex);
                });

        // Track worker shutdown so callers can optionally await a clean
        // teardown (heartbeats, listeners, executors) without arbitrary
        // sleeps.
        if (worker != null) {
            workerShutdownFuture = worker.getState() != null
                    ? worker.getState().completion()
                    : null;
        }

        return workerFuture;
    }

    public CompletableFuture<Void> stopPipeline() {
        LOG.info(
                "PipelineExecution: Stop signal detected, beginning graceful shutdown.");
        ofNullable(worker).ifPresent(PipelineWorker::stop);
        ofNullable(coordinator).ifPresent(PipelineCoordinator::stop);

        // Cancel both worker and coordinator futures to unblock any code
        // waiting on them. This is critical to allow commands to complete
        // and the JVM to exit properly.
        if (workerFuture != null && !workerFuture.isDone()) {
            LOG.info("Cancelling worker future to allow graceful shutdown.");
            workerFuture.cancel(true);
        }
        if (coordinatorFuture != null && !coordinatorFuture.isDone()) {
            LOG.info(
                    "Cancelling coordinator future to allow graceful shutdown.");
            coordinatorFuture.cancel(true);
        }

        // Optionally wait briefly for worker shutdown completion so we
        // don't prolong termination unnecessarily. We ignore timeouts or
        // interruptions here to avoid blocking shutdown.
        if (workerShutdownFuture != null && !workerShutdownFuture.isDone()) {
            try {
                workerShutdownFuture.get(SHUTDOWN_AWAIT_SECONDS,
                        TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.debug("Interrupted while waiting for worker shutdown.");
            } catch (Exception e) {
                LOG.debug("Timed out or interrupted while waiting for worker "
                        + "shutdown: {}", e.toString());
            }
        }

        close();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
        if (coordinatorListenerRegistered) {
            cluster.removeCoordinatorChangeListener(coordChangeListener);
        }
        executor.shutdown(); // disable new tasks, let running complete
        try {
            if (!executor.awaitTermination(SHUTDOWN_AWAIT_SECONDS,
                    TimeUnit.SECONDS)) {
                executor.shutdownNow(); // cancel running tasks
                if (!executor.awaitTermination(SHUTDOWN_AWAIT_SECONDS,
                        TimeUnit.SECONDS)) {
                    LOG.warn("Executor did not terminate cleanly.");
                }
            }
        } catch (InterruptedException ie) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    //--- Private methods ------------------------------------------------------

    private void logMode(String reason) {
        if (LOG.isInfoEnabled()) {
            LOG.info("Pipeline {} node {} role: {} ({}).",
                    pipeline.getId(),
                    cluster.getLocalNode().getNodeName(),
                    isCoordinator ? "COORDINATOR" : "WORKER",
                    reason);
        }
    }

    private void handleCoordinatorChange(boolean isCoord) {
        synchronized (this) {
            isCoordinator = isCoord;
            if (!executed || resultFuture.isDone()) {
                return; // ignore before start or after completion
            }
            // new coordinator:
            if (isCoord && coordinator == null) {
                // Promotion: only if we do not already have a coordinator
                // instance
                logMode("promotion to coordinator");
                startCoordinator();
            } else if (!isCoord && coordinator != null) {
                // demotion:
                logMode("demotion from coordinator");
                coordinator = null;
            }
        }
    }

    private void startCoordinator() {
        LOG.debug("Starting pipeline coordinator for {} on node {}",
                pipeline.getId(), cluster.getLocalNode().getNodeName());
        coordinator = new PipelineCoordinator(cluster, pipeline);
        coordinatorFuture =
                CompletableFuture.runAsync(coordinator::start, executor)
                        .whenComplete((result, ex) -> {
                            // This code will always run, on success or failure.
                            if (ex != null) {
                                // An exception occurred in the pipeline start.
                                LOG.error("Pipeline coordinator failed", ex);
                                try {
                                    resultFuture.completeExceptionally(ex);
                                } finally {
                                    ExceptionSwallower.close(coordinator);
                                }
                            } else {
                                // Pipeline coordinator completed successfully
                                LOG.info("Pipeline coordinator completed "
                                        + "successfully for {}",
                                        pipeline.getId());
                                try {
                                    // Create a success result
                                    var pipelineResult = PipelineResult
                                            .builder()
                                            .pipelineId(pipeline.getId())
                                            .status(PipelineStatus.COMPLETED)
                                            .build();
                                    resultFuture.complete(pipelineResult);
                                } finally {
                                    ExceptionSwallower.close(coordinator);
                                }
                            }

                        });
    }

    private ThreadFactory createThreadFactory() {
        return new ThreadFactory() {
            private final ThreadFactory delegate =
                    Executors.defaultThreadFactory();
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                var t = delegate.newThread(r);
                t.setName("PIPE-" + cluster.getLocalNode().getNodeName()
                        + "-" + seq.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
    }
}
