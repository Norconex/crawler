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
package com.norconex.crawler.core.cluster.impl.hazelcast;

import java.util.Objects;

import com.hazelcast.collection.IQueue;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.norconex.commons.lang.Sleeper;
import com.norconex.crawler.core.cluster.pipeline.Pipeline;
import com.norconex.crawler.core.cluster.pipeline.PipelineStatus;
import com.norconex.crawler.core.cluster.pipeline.StepRecord;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class HazelcastUtil {
    private HazelcastUtil() {
    }

    public static boolean isPersistent(
            HazelcastInstance hazelcast, String name) {
        try {
            var cfg = hazelcast.getConfig();
            // Try map first
            try {
                var mcfg = cfg.getMapConfig(name);
                var ms = mcfg.getMapStoreConfig();
                if (ms != null && ms.isEnabled()) {
                    return true;
                }
            } catch (Exception e) {
                // ignore
            }
            // Try queue
            try {
                var qcfg = cfg.getQueueConfig(name);
                var qs = qcfg.getQueueStoreConfig();
                return qs != null && qs.isEnabled();
            } catch (Exception e) {
                // ignore
            }
            return false;
        } catch (Exception e) {
            LOG.debug("Could not determine persistence for '{}': {}",
                    name, e.toString());
            return false;
        }
    }

    public static boolean isSupportedCacheType(Object obj) {
        return obj instanceof IMap || obj instanceof IQueue;
    }

    /**
     * Resolves a HazelcastInstance for use by a store factory, with
     * deterministic fallback behavior to handle EAGER store initialization
     * timing while preventing accidental binding to stale clusters.
     * <p>
     * Resolution order:
     * <ol>
     *   <li>Use the already-resolved instance if available</li>
     *   <li>Look up by instance name if provided</li>
     *   <li>Fall back to the single JVM-local instance if exactly one exists
     *       (handles EAGER loading before instance injection)</li>
     *   <li>Fail fast if ambiguous (multiple instances) or none available</li>
     * </ol>
     *
     * @param hazelcastInstance the already-resolved instance (may be null)
     * @param hazelcastInstanceName the instance name to look up (may be null)
     * @param storeName the name of the store/cache requesting the instance
     * @param storeType the type of store (e.g., "map", "queue") for error messages
     * @return the resolved HazelcastInstance, never null
     * @throws IllegalStateException if no instance can be resolved or if ambiguous
     */
    public static HazelcastInstance resolveStoreInstance(
            HazelcastInstance hazelcastInstance,
            String hazelcastInstanceName,
            String storeName,
            String storeType) {

        HazelcastInstance hz = hazelcastInstance;
        if (hz == null && hazelcastInstanceName != null) {
            hz = HazelcastCacheManager
                    .getHazelcastInstance(hazelcastInstanceName);
        }

        // Safe fallback: allow using the single JVM-local instance if that's the only
        // one running. This handles EAGER store loading during initialization when
        // the factory hasn't yet been injected with the instance reference.
        if (hz == null) {
            var instances =
                    com.hazelcast.core.Hazelcast.getAllHazelcastInstances();
            if (instances.size() == 1) {
                hz = instances.iterator().next();
                LOG.debug(
                        "Using single JVM-local HazelcastInstance for {} store '{}' "
                                + "(instance name: '{}')",
                        storeType, storeName, hz.getName());
            } else if (instances.size() > 1) {
                throw new IllegalStateException(
                        "Cannot resolve HazelcastInstance for " + storeType
                                + " store '"
                                + storeName + "': "
                                + instances.size()
                                + " instances running in JVM. "
                                + "Expected instance name: '"
                                + hazelcastInstanceName + "'. "
                                + "Refusing ambiguous fallback to prevent binding to wrong cluster.");
            }
        }

        if (hz == null) {
            throw new IllegalStateException(
                    "HazelcastInstance is not available for " + storeType
                            + " store '"
                            + storeName + "'. "
                            + "Expected instance name: '"
                            + hazelcastInstanceName + "'. "
                            + "No Hazelcast instances found in JVM.");
        }

        return hz;
    }

    /**
     * Whether a pipeline should be considered "terminated", either by
     * completing all steps (success), or having a non-COMPLETED terminal
     * status on any of the steps (aborting the pipeline), or, having
     * a completed step when stopping was requested.
     * A {@code null} step record suggests no steps have yet run so
     * we consider it non-terminated.
     * @param pipeline the pipeline
     * @param stepRecord the record of the last step ran/attempted.
     * @param stopRequested whether stop was requested
     * @return true if terminated
     */
    public static boolean isPipelineTerminated(
            Pipeline pipeline,
            StepRecord stepRecord,
            boolean stopRequested) {

        if (stepRecord == null || stepRecord.getStatus() == null) {
            return false;
        }

        var terminal = stepRecord.getStatus().isTerminal();

        // stop was requested and the current step has completed
        if (terminal && stopRequested) {
            return true;
        }

        // current step is the same as last step
        var isLastStep = Objects.equals(stepRecord.getStepId(),
                pipeline.getLastStep().getId());

        // all steps have terminated
        if (terminal && isLastStep) {
            return true;
        }

        // the step is a terminal, but non-COMPLETED, which means
        // the pipeline fail and we consider terminated even if not last
        var isCompleted = stepRecord.getStatus() == PipelineStatus.COMPLETED;
        return terminal && !isCompleted;
    }

    // either the first pipeline step or an existing one (joining mid-pipe).
    public static StepRecord currentPipelineStepRecordOrFirst(
            HazelcastCluster cluster, Pipeline pipeline) {
        var pipelineKey = CacheKeys.pipelineKey(cluster, pipeline);
        var stepRec = cluster.getCacheManager().getPipelineStepCache()
                .get(pipelineKey)
                .orElse(null);
        if (stepRec == null) {
            var firstStep = pipeline.getFirstStep();
            if (firstStep == null) {
                throw new IllegalStateException("Pipeline \"%s\" has no steps."
                        .formatted(pipeline.getId()));
            }
            var stepId = firstStep.getId();
            stepRec = new StepRecord();
            stepRec.setPipelineId(pipeline.getId());
            stepRec.setStepId(stepId);
            stepRec.setStatus(PipelineStatus.PENDING);
            stepRec.setRunId(cluster.getCrawlSession().getCrawlRunId());
            stepRec.setUpdatedAt(System.currentTimeMillis());
        } else {
            LOG.info("Resuming pipeline \"{}\" at step \"{}\" from "
                    + "status {} (runId={})",
                    stepRec.getPipelineId(),
                    stepRec.getStepId(),
                    stepRec.getStatus(),
                    stepRec.getRunId());
            // When resuming a stopped/terminal pipeline, we need to
            // transition the step back to PENDING for the new run so
            // execution can continue instead of short-circuiting on a
            // terminal status from the previous run.
            if (stepRec.getStatus().isTerminal()) {
                stepRec.setStatus(PipelineStatus.PENDING);
                stepRec.setRunId(
                        cluster.getCrawlSession().getCrawlRunId());
                stepRec.setUpdatedAt(System.currentTimeMillis());
                cluster.getCacheManager().getPipelineStepCache()
                        .put(pipelineKey, stepRec);

                // Note: We do NOT clear worker statuses here. Worker statuses
                // contain the runId, so when nodes re-register for the new
                // runId, they'll naturally create new entries. The old entries
                // will be ignored by the coordinator since it checks runId.
                LOG.info("Resuming pipeline \"{}\" at step \"{}\" with new "
                        + "runId \"{}\"",
                        pipeline.getId(), stepRec.getStepId(),
                        stepRec.getRunId());
            }
        }
        return stepRec;
    }

    public static boolean isClusterRunning(HazelcastCluster cluster) {
        return ((HazelcastInstance) cluster.getCacheManager().vendor())
                .getLifecycleService().isRunning();
    }

    public static void waitForClusterWarmUp(HazelcastCluster cluster) {
        // Allow up to ~10 seconds for cluster to stabilize
        var deadline = System.currentTimeMillis() + 10_000;
        var lastSize = cluster.getNodeCount();
        var stableTicks = 0;
        var coordinatorElected = false;

        while (System.currentTimeMillis() < deadline) {
            if (!HazelcastUtil.isClusterRunning(cluster)) {
                Sleeper.sleepMillis(25);
                continue;
            }
            var currentSize = cluster.getNodeCount();
            if (currentSize == lastSize) {
                stableTicks++;
            } else {
                stableTicks = 0;
            }
            lastSize = currentSize;

            // Also verify coordinator election has completed
            if (!coordinatorElected && hasStableCoordinator(cluster)) {
                coordinatorElected = true;
                LOG.debug("Coordinator election completed");
            }

            if (stableTicks >= 5 && coordinatorElected) {
                return;
            }
            Sleeper.sleepMillis(50);
        }
        LOG.warn("Cluster warm-up timed out (stable={}, coordinator={}); "
                + "proceeding anyway.",
                stableTicks >= 5, coordinatorElected);
    }

    /**
     * Check if the cluster has a stable coordinator elected.
     * @param cluster the cluster to check
     * @return true if a coordinator is elected and consistent
     */
    private static boolean hasStableCoordinator(HazelcastCluster cluster) {
        try {
            var hazelcast =
                    (HazelcastInstance) cluster.getCacheManager().vendor();
            if (hazelcast == null
                    || !hazelcast.getLifecycleService().isRunning()) {
                return false;
            }
            var members = hazelcast.getCluster().getMembers();
            // There should be at least one member (the oldest is coordinator)
            return !members.isEmpty();
        } catch (Exception e) {
            LOG.debug("Error checking coordinator status: {}", e.toString());
            return false;
        }
    }
}
