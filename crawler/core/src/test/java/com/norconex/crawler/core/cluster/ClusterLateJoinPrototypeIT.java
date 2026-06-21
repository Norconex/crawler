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
package com.norconex.crawler.core.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.cluster.impl.hazelcast.HazelcastClusterConnector;
import com.norconex.crawler.core.cluster.impl.hazelcast.JdbcHazelcastConfigurer;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.junit.WithTestWatcherLogging;
import com.norconex.crawler.core.mocks.fetch.MockFetcher;
import com.norconex.crawler.core.test.CrawlerTestHarness;
import com.norconex.crawler.core.test.CrawlerTestInstrument;

@WithTestWatcherLogging
@Timeout(180)
class ClusterLateJoinPrototypeIT {

    private static final Duration CLUSTER_JOIN_WAIT =
            Duration.ofSeconds(120);
    private static final Duration RESULT_RECORD_INTERVAL =
            Duration.ofMillis(200);
    private static final Duration TEST_IDLE_TIMEOUT = Duration.ofSeconds(5);

    @TempDir
    private Path tempDir;

    @Test
    @Timeout(180)
    void lateJoinUsingClusterJoinGateFetcher() throws Exception {
        var numOfRefs = 100;
        var initialNodeNames = new String[] { "node-1" };
        var lateNodeName = "node-2";
        var completionDeadlineNanos = System.nanoTime()
                + Duration.ofSeconds(170).toNanos();

        try (var harness = newHarness(instrument -> instrument
                .setRecordEvents(true)
                .setRecordCaches(false)
                .setConfigModifier(cfg -> {
                    baseConfig(numOfRefs, 10).accept(cfg);
                    cfg.setId("prototype-late-join-"
                            + numOfRefs);
                    cfg.setMaxQueueBatchSize(1);

                    var connector = (HazelcastClusterConnector) cfg
                            .getClusterConfig()
                            .getConnector();
                    var hzConfig = connector
                            .getConfiguration();
                    var configurer = (JdbcHazelcastConfigurer) hzConfig
                            .getConfigurer();
                    configurer.setAutoDiscoveryEnabled(
                            true);
                })
                .setNodeConfigModifier((nodeName, cfg) -> {
                    if ("node-1".equals(nodeName)) {
                        cfg.setNumThreads(1);
                        cfg.setFetchers(List.of(
                                new ClusterJoinGateFetcher()
                                        .setRequiredNodeCount(
                                                2)
                                        .setGatedRefs(List
                                                .of("ref-0"))));
                        return;
                    }
                    cfg.setNumThreads(2);
                    cfg.setFetchers(List.of(
                            Configurable.configure(
                                    new MockFetcher(),
                                    fcfg -> fcfg.setDelay(
                                            Duration.ZERO))));
                }))) {
            var initialFuture =
                    harness.launchAsync(initialNodeNames);
            harness.waitFor(CLUSTER_JOIN_WAIT).nodeToHaveFired(
                    initialNodeNames[0],
                    CrawlerEvent.DOCUMENT_PROCESSING_BEGIN);
            assertThat(initialFuture.isDone()).isFalse();

            var lateFuture = harness.launchAsync(lateNodeName);
            harness.waitFor(CLUSTER_JOIN_WAIT).nodeToHaveFired(
                    lateNodeName,
                    CrawlerEvent.DOCUMENT_PROCESSING_BEGIN);
            harness.waitFor(CLUSTER_JOIN_WAIT).nodeToHaveFired(
                    lateNodeName,
                    CrawlerEvent.DOCUMENT_IMPORTED);

            CompletableFuture.allOf(initialFuture, lateFuture)
                    .get(remainingMillis(
                            completionDeadlineNanos),
                            TimeUnit.MILLISECONDS);

            var initialResult = initialFuture.join();
            var lateResult = lateFuture.join();

            var lateOutput = lateResult.getNodeOutput(lateNodeName);
            assertThat(lateOutput).isNotNull();
            assertThat(lateOutput.getEventNames())
                    .contains(CrawlerEvent.DOCUMENT_PROCESSING_BEGIN);
            assertThat(lateOutput.getEventNameBag()
                    .getCount(CrawlerEvent.DOCUMENT_IMPORTED))
                            .isGreaterThan(0);
            assertThat(
                    initialResult.getNodeOutput("node-1")
                            .getEventNameBag()
                            .getCount(CrawlerEvent.DOCUMENT_IMPORTED))
                                    .isGreaterThan(0);
        }
    }

    private static long remainingMillis(long deadlineNanos) {
        var remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new IllegalStateException(
                    "Prototype wait budget exhausted.");
        }
        return Math.max(1,
                TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    private CrawlerTestHarness newHarness(
            Consumer<CrawlerTestInstrument> instrumentModifier) {
        var instrument = new CrawlerTestInstrument()
                .setRecordInterval(RESULT_RECORD_INTERVAL)
                .setWorkDir(tempDir)
                .setNewJvm(false)
                .setClustered(true);
        instrumentModifier.accept(instrument);
        return new CrawlerTestHarness(instrument);
    }

    private static Consumer<CrawlerConfig> baseConfig(int numOfRefs,
            long delayMs) {
        return cfg -> cfg
                .setStartReferences(IntStream
                        .range(0, numOfRefs)
                        .mapToObj(i -> "ref-" + i)
                        .toList())
                .setId("prototype-crawler-" + numOfRefs)
                .setMaxQueueBatchSize(10)
                .setNumThreads(2)
                .setIdleTimeout(TEST_IDLE_TIMEOUT)
                .setFetchers(List.of(Configurable.configure(
                        new MockFetcher(),
                        fcfg -> fcfg.setDelay(Duration
                                .ofMillis(delayMs)))));
    }
}
