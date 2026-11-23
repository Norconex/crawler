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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.hazelcast.core.HazelcastInstance;
import com.norconex.commons.lang.config.Configurable;
import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.Crawler;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.core.mocks.fetch.MockFetcher;
import com.norconex.crawler.core.test.CrawlerTestDriver;

import lombok.extern.slf4j.Slf4j;

/**
 * Demonstrates running two crawler nodes <em>in the same JVM</em> using
 * {@link MockNetworkClusterConnector}.  The Hazelcast mock-network layer
 * eliminates TCP port allocation and drops per-node startup time from
 * seconds to milliseconds.
 *
 * <p>This test proves the embedded multi-instance pattern works for full
 * crawler lifecycle (init → crawl → close) without spawning child JVMs.
 * It uses an in-memory H2 database shared by both nodes.</p>
 */
@Slf4j
@Timeout(60)
class EmbeddedClusterCrawlIT {

    private static final long CRAWL_TIMEOUT_SECONDS = 90;
    private static final long EXECUTOR_SHUTDOWN_SECONDS = 15;

    @TempDir
    java.nio.file.Path tempDir;

    @AfterEach
    void tearDown() {
        HazelcastTestSupport.shutdownAll();
    }

    @Test
    @Timeout(120)
    void twoEmbeddedNodesCrawlAllReferences() throws Exception {
        var numRefs = 8;
        var crawlerId = "embedded-cluster-test";
        var clusterName = "embed-" + UUID.randomUUID().toString()
                .substring(0, 8);

        // Shared H2 in-memory database so both nodes see the same tables.
        var sharedJdbcUrl = "jdbc:h2:mem:" + clusterName
                + ";DB_CLOSE_DELAY=-1";
        var h2SqlMerge = "MERGE INTO \"{tableName}\" (k, v)\n"
                + "KEY(k)\nVALUES (?, ?)";

        var startRefs = java.util.stream.IntStream.range(0, numRefs)
                .mapToObj(i -> "ref-" + i)
                .toList();

        // Collect events from both nodes.
        var allEvents = new CopyOnWriteArrayList<String>();

        // Build per-node configs sharing the same cluster + DB.
        var cfg1 = buildConfig(crawlerId, clusterName,
                sharedJdbcUrl, h2SqlMerge, startRefs,
                tempDir.resolve("node-1"));
        var cfg2 = buildConfig(crawlerId, clusterName,
                sharedJdbcUrl, h2SqlMerge, startRefs,
                tempDir.resolve("node-2"));

        // Attach lightweight event recorders.
        cfg1.addEventListener(event -> {
            if (event instanceof CrawlerEvent ce) {
                allEvents.add(ce.getName());
            }
        });
        cfg2.addEventListener(event -> {
            if (event instanceof CrawlerEvent ce) {
                allEvents.add(ce.getName());
            }
        });

        // Launch both nodes concurrently in the same JVM.
        ExecutorService executor =
                Executors.newFixedThreadPool(2, r -> {
                    var thread = new Thread(r,
                            "embedded-cluster-crawl");
                    thread.setDaemon(false);
                    return thread;
                });
        Future<?> f1 = null;
        Future<?> f2 = null;
        try {
            f1 = executor.submit(
                    () -> new Crawler(CrawlerTestDriver
                            .create(),
                            cfg1).crawl());
            f2 = executor.submit(
                    () -> new Crawler(CrawlerTestDriver
                            .create(),
                            cfg2).crawl());

            f1.get(CRAWL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            f2.get(CRAWL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            cancel(f1);
            cancel(f2);
            HazelcastTestSupport.shutdownAll();
            throw e;
        } finally {
            cancel(f1);
            cancel(f2);
            executor.shutdownNow();
            executor.awaitTermination(
                    EXECUTOR_SHUTDOWN_SECONDS,
                    TimeUnit.SECONDS);
        }

        // Both nodes should have completed their crawl lifecycle.
        var importCount = allEvents.stream()
                .filter(CrawlerEvent.DOCUMENT_IMPORTED::equals)
                .count();
        LOG.info("Embedded cluster test: {} imports across 2 nodes",
                importCount);
        assertThat(importCount).isEqualTo(numRefs);
    }

    /**
     * A node that dies holding documents it took for processing does not
     * lose them: the node still working puts them back in the queue and
     * processes them in the same run.
     */
    @Test
    @Timeout(120)
    void documentsOfNodeThatDiedAreProcessedByTheOther() throws Exception {
        var numRefs = 20;
        var crawlerId = "embedded-cluster-dead-node-test";
        var clusterName = "embed-" + UUID.randomUUID().toString()
                .substring(0, 8);
        var sharedJdbcUrl = "jdbc:h2:mem:" + clusterName
                + ";DB_CLOSE_DELAY=-1";
        var h2SqlMerge = "MERGE INTO \"{tableName}\" (k, v)\n"
                + "KEY(k)\nVALUES (?, ?)";
        var startRefs = java.util.stream.IntStream.range(0, numRefs)
                .mapToObj(i -> "ref-" + i)
                .toList();

        // Node 1 is slow enough for node 2 to join and take some.
        var cfg1 = buildConfig(crawlerId, clusterName,
                sharedJdbcUrl, h2SqlMerge, startRefs,
                tempDir.resolve("node-1"), Duration.ofSeconds(1));
        // Node 2 never finishes a document: it hangs until killed.
        var cfg2 = buildConfig(crawlerId, clusterName,
                sharedJdbcUrl, h2SqlMerge, startRefs,
                tempDir.resolve("node-2"), Duration.ofMinutes(10));
        cfg1.setMaxQueueBatchSize(2);
        cfg2.setMaxQueueBatchSize(2);
        // A node's share of the cluster data must survive it, as with the
        // clustered default (one backup).
        withOneBackup(cfg1);
        withOneBackup(cfg2);

        var node1Imported = new CopyOnWriteArrayList<String>();
        var node1Started = new CountDownLatch(1);
        cfg1.addEventListener(event -> {
            if (event instanceof CrawlerEvent ce) {
                if (CrawlerEvent.DOCUMENT_PROCESSING_BEGIN
                        .equals(ce.getName())) {
                    node1Started.countDown();
                } else if (CrawlerEvent.DOCUMENT_IMPORTED
                        .equals(ce.getName())) {
                    node1Imported.add(ce.getCrawlEntry().getReference());
                }
            }
        });
        var node2Session = new AtomicReference<CrawlerSession>();
        var node2Holds = new CountDownLatch(1);
        cfg2.addEventListener(event -> {
            if (event instanceof CrawlerEvent ce
                    && CrawlerEvent.DOCUMENT_PROCESSING_BEGIN
                            .equals(ce.getName())) {
                node2Session.compareAndSet(null, ce.getCrawlSession());
                node2Holds.countDown();
            }
        });

        ExecutorService executor =
                Executors.newFixedThreadPool(2, r -> {
                    var thread = new Thread(r,
                            "embedded-cluster-crawl");
                    thread.setDaemon(true);
                    return thread;
                });
        Future<?> f1 = null;
        Future<?> f2 = null;
        try {
            f1 = executor.submit(() -> new Crawler(
                    CrawlerTestDriver.create(), cfg1).crawl());
            assertThat(node1Started.await(60, TimeUnit.SECONDS)).isTrue();
            f2 = executor.submit(() -> new Crawler(
                    CrawlerTestDriver.create(), cfg2).crawl());
            assertThat(node2Holds.await(60, TimeUnit.SECONDS)).isTrue();

            // Node 2 dies with the documents it took.
            var node2Hazelcast = (HazelcastInstance) node2Session.get()
                    .getCluster().getCacheManager().vendor();
            node2Hazelcast.getLifecycleService().terminate();

            f1.get(CRAWL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            cancel(f1);
            cancel(f2);
            executor.shutdownNow();
            executor.awaitTermination(
                    EXECUTOR_SHUTDOWN_SECONDS, TimeUnit.SECONDS);
        }

        LOG.info("Node 1 imported: {}", node1Imported);
        assertThat(node1Imported)
                .containsExactlyInAnyOrderElementsOf(startRefs);
    }

    private static CrawlerConfig buildConfig(
            String crawlerId,
            String clusterName,
            String jdbcUrl,
            String sqlMerge,
            List<String> startRefs,
            java.nio.file.Path workDir) {
        return buildConfig(crawlerId, clusterName, jdbcUrl, sqlMerge,
                startRefs, workDir, Duration.ofMillis(10));
    }

    private static CrawlerConfig buildConfig(
            String crawlerId,
            String clusterName,
            String jdbcUrl,
            String sqlMerge,
            List<String> startRefs,
            java.nio.file.Path workDir,
            Duration fetchDelay) {

        var cfg = new CrawlerConfig();
        cfg.setId(crawlerId);
        cfg.setWorkDir(workDir);
        cfg.setStartReferences(startRefs);
        cfg.setNumThreads(2);
        cfg.setIdleTimeout(Duration.ofSeconds(2));
        cfg.setFetchers(List.of(Configurable.configure(
                new MockFetcher(),
                fcfg -> fcfg.setDelay(fetchDelay))));

        // Cluster: embedded mock-network connector
        cfg.getClusterConfig().setClustered(true);
        cfg.getClusterConfig().setAdminDisabled(true);
        var connector = new MockNetworkClusterConnector();
        cfg.getClusterConfig().setConnector(connector);

        // JDBC + Hazelcast tuning
        var hzConfig = connector.getConfiguration();
        hzConfig.setClusterName(clusterName);

        var configurer =
                (JdbcHazelcastConfigurer) hzConfig
                        .getConfigurer();
        configurer
                .setJdbcUrl(jdbcUrl)
                .setSqlMerge(sqlMerge)
                .setBackupCount(0)
                .setJetEnabled(false);

        return cfg;
    }

    private static void withOneBackup(CrawlerConfig cfg) {
        var connector = (MockNetworkClusterConnector) cfg.getClusterConfig()
                .getConnector();
        ((JdbcHazelcastConfigurer) connector.getConfiguration()
                .getConfigurer()).setBackupCount(1);
    }

    private static void cancel(Future<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }
}
