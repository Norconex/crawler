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
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.apache.commons.lang3.reflect.FieldUtils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.norconex.crawler.core.cluster.Cluster;
import com.norconex.crawler.core.cluster.pipeline.PipelineStatus;
import com.norconex.crawler.core.cluster.pipeline.StepRecord;
import com.norconex.crawler.core.cluster.support.InMemoryCacheMap;
import com.norconex.crawler.core.session.CrawlerSession;

/**
 * Tests for {@link HazelcastCluster#workingNodeNames}: which cluster members
 * are still working on the crawl, from their worker heartbeats.
 */
class HazelcastClusterWorkingNodesTest {

    private static final String RUN = "run-2";
    private static final String PIPELINE_KEY = "session-1:crawl";
    private static final long EXPIRY_MS = 5_000;

    private InMemoryCacheMap<StepRecord> workerCache;
    private InMemoryCacheMap<StepRecord> stepCache;

    @BeforeEach
    void setUp() {
        workerCache = new InMemoryCacheMap<>("workers");
        stepCache = new InMemoryCacheMap<>("steps");
        stepCache.put(PIPELINE_KEY,
                stepRecord("process", PipelineStatus.RUNNING,
                        RUN, now()));
    }

    @Test
    void memberWithRecentHeartbeatIsWorking() {
        heartbeat("node-a", "process", PipelineStatus.RUNNING, RUN, now());

        assertThat(working("node-a")).containsExactly("node-a");
    }

    @Test
    void memberWithNoHeartbeatYetIsWorking() {
        assertThat(working("node-a")).containsExactly("node-a");
    }

    @Test
    void nodeThatLeftTheClusterIsNotWorking() {
        heartbeat("node-a", "process", PipelineStatus.RUNNING, RUN, now());
        heartbeat("node-b", "process", PipelineStatus.RUNNING, RUN, now());

        assertThat(working("node-a")).containsExactly("node-a");
    }

    @Test
    void memberWithExpiredHeartbeatIsNotWorking() {
        heartbeat("node-a", "process", PipelineStatus.RUNNING, RUN,
                now() - EXPIRY_MS - 5_000);
        heartbeat("node-b", "process", PipelineStatus.RUNNING, RUN, now());

        assertThat(working("node-a", "node-b")).containsExactly("node-b");
    }

    @Test
    void memberDoneWithTheRunningStepIsNotWorking() {
        heartbeat("node-a", "process", PipelineStatus.COMPLETED, RUN, now());

        assertThat(working("node-a")).isEmpty();
    }

    @Test
    void memberDoneWithAnEarlierStepIsWorking() {
        // About to start the running step: its record is still the last one.
        heartbeat("node-a", "queue", PipelineStatus.COMPLETED, RUN, now());

        assertThat(working("node-a")).containsExactly("node-a");
    }

    @Test
    void heartbeatsOfAnotherRunAreIgnored() {
        heartbeat("node-a", "process", PipelineStatus.RUNNING, "run-1",
                now() - EXPIRY_MS - 5_000);

        assertThat(working("node-a")).containsExactly("node-a");
    }

    @Test
    void latestHeartbeatOfANodeDecides() {
        // An older expired record of the same node does not count.
        workerCache.put("other-pipeline:" + RUN + ":node-a",
                stepRecord("process", PipelineStatus.RUNNING, RUN,
                        now() - EXPIRY_MS - 5_000));
        heartbeat("node-a", "process", PipelineStatus.RUNNING, RUN, now());
        // Older again, seen after the newest.
        workerCache.put("third:" + RUN + ":node-a",
                stepRecord("process", PipelineStatus.RUNNING, RUN,
                        now() - EXPIRY_MS - 9_000));

        assertThat(working("node-a")).containsExactly("node-a");
    }

    @Test
    void doneMemberIsWorkingIfItsPipelineIsAtAnotherRunOrUnknown() {
        stepCache.put(PIPELINE_KEY, stepRecord("process",
                PipelineStatus.RUNNING, "run-1", now()));
        heartbeat("node-a", "process", PipelineStatus.COMPLETED, RUN, now());
        // A key not shaped like a worker key: its pipeline is unknown.
        workerCache.put("plain:node-b", stepRecord("process",
                PipelineStatus.COMPLETED, RUN, now()));
        workerCache.put("x:node-c", stepRecord("process",
                null, RUN, now()));

        assertThat(working("node-a", "node-b", "node-c"))
                .containsExactly("node-a", "node-b", "node-c");
    }

    @Test
    void defaultIsTheClusterMembers() {
        var cluster = mock(Cluster.class, CALLS_REAL_METHODS);
        when(cluster.getNodeNames()).thenReturn(List.of("node-a", "node-b"));

        assertThat(cluster.getWorkingNodeNames())
                .containsExactlyInAnyOrder("node-a", "node-b");
    }

    @Test
    void standaloneHazelcastClusterIsItsMembers() {
        var cluster = spy(new HazelcastCluster(
                new HazelcastClusterConnectorConfig()));
        doReturn(List.of("node-a")).when(cluster).getNodeNames();

        assertThat(cluster.getWorkingNodeNames()).containsExactly("node-a");
    }

    @Test
    void clusteredHazelcastClusterUsesHeartbeats() throws Exception {
        var cluster = spy(new HazelcastCluster(
                new HazelcastClusterConnectorConfig()));
        doReturn(List.of("node-a", "node-b")).when(cluster).getNodeNames();
        var cacheManager = mock(HazelcastCacheManager.class);
        when(cacheManager.getPipelineWorkerStatusCache())
                .thenReturn(workerCache);
        when(cacheManager.getPipelineStepCache()).thenReturn(stepCache);
        var session = mock(CrawlerSession.class);
        when(session.getCrawlRunId()).thenReturn(RUN);
        FieldUtils.writeField(cluster, "clustered", true, true);
        FieldUtils.writeField(cluster, "cacheManager", cacheManager, true);
        FieldUtils.writeField(cluster, "session", session, true);
        heartbeat("node-a", "process", PipelineStatus.RUNNING, RUN, now());
        heartbeat("node-b", "process", PipelineStatus.RUNNING, RUN,
                now() - 60_000);

        assertThat(cluster.getWorkingNodeNames()).containsExactly("node-a");
    }

    @Test
    void nodeExpiryTimeoutHasAFloorAndADefault() {
        var config = new HazelcastClusterConnectorConfig();
        assertThat(HazelcastUtil.nodeExpiryTimeoutMs(config))
                .isEqualTo(30_000);
        assertThat(HazelcastUtil.nodeExpiryTimeoutMs(null))
                .isEqualTo(30_000);
        config.setNodeExpiryTimeout(null);
        assertThat(HazelcastUtil.nodeExpiryTimeoutMs(config))
                .isEqualTo(30_000);
        config.setNodeExpiryTimeout(Duration.ofSeconds(1));
        assertThat(HazelcastUtil.nodeExpiryTimeoutMs(config))
                .isEqualTo(HazelcastUtil.MIN_NODE_EXPIRY_TIMEOUT_MS);
        config.setNodeExpiryTimeout(Duration.ofMinutes(2));
        assertThat(HazelcastUtil.nodeExpiryTimeoutMs(config))
                .isEqualTo(120_000);
    }

    private List<String> working(String... members) {
        return HazelcastCluster.workingNodeNames(List.of(members),
                workerCache, stepCache, RUN, EXPIRY_MS)
                .stream().sorted().toList();
    }

    private void heartbeat(String node, String stepId,
            PipelineStatus status, String runId, long updatedAt) {
        workerCache.put(PIPELINE_KEY + ":" + runId + ":" + node,
                stepRecord(stepId, status, runId, updatedAt));
    }

    private static StepRecord stepRecord(String stepId, PipelineStatus status,
            String runId, long updatedAt) {
        return new StepRecord()
                .setPipelineId("crawl")
                .setStepId(stepId)
                .setStatus(status)
                .setRunId(runId)
                .setUpdatedAt(updatedAt);
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
