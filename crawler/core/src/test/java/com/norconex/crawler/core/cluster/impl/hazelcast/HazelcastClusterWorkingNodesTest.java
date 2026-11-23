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

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.norconex.crawler.core.cluster.pipeline.PipelineStatus;
import com.norconex.crawler.core.cluster.pipeline.StepRecord;
import com.norconex.crawler.core.cluster.support.InMemoryCacheMap;

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
        stepCache.put(PIPELINE_KEY, record("process", PipelineStatus.RUNNING,
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

    private List<String> working(String... members) {
        return HazelcastCluster.workingNodeNames(List.of(members),
                workerCache, stepCache, RUN, EXPIRY_MS)
                .stream().sorted().toList();
    }

    private void heartbeat(String node, String stepId,
            PipelineStatus status, String runId, long updatedAt) {
        workerCache.put(PIPELINE_KEY + ":" + runId + ":" + node,
                record(stepId, status, runId, updatedAt));
    }

    private static StepRecord record(String stepId, PipelineStatus status,
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
