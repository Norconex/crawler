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
package com.norconex.crawler.core.cmd.crawl.pipeline.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.crawler.core.junit.WithLogLevel;
import com.norconex.commons.lang.config.Configurable;
import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.junit.WithTestWatcherLogging;
import com.norconex.crawler.core.mocks.fetch.MockFetcher;
import com.norconex.crawler.core.test.CrawlerTestHarness;
import com.norconex.crawler.core.test.CrawlerTestInstrument;

/**
 * End-to-end tests for the exception-handling paths in
 * {@code CrawlerProcessStep.handleExceptionAndCheckIfStopCrawler}.
 *
 * <p>To trigger {@code handleExceptionAndCheckIfStopCrawler}, an exception
 * must escape {@code ProcessUpsert.execute()}.  Because {@code MultiFetcher}
 * internally catches all exceptions thrown from
 * {@code Fetcher.fetch()}, the exception is instead injected in
 * {@link MockFetcher#acceptRequest} via {@code setThrowOnAccept(true)} /
 * {@code setThrowOnAcceptRefs(refs)}.  This method is called by
 * {@code MultiFetcher.fetch()} without a surrounding try-catch, so the
 * resulting {@link RuntimeException} propagates all the way to
 * {@code processNextInQueue}'s catch block, exercising
 * {@code handleExceptionAndCheckIfStopCrawler}.</p>
 *
 * <p>All tests run in standalone (single-node, in-JVM, H2) mode for speed.</p>
 */
@Timeout(60)
@WithTestWatcherLogging
@WithLogLevel(value = "OFF", classes = CrawlerProcessStep.class) // tests expect exceptions
class CrawlerProcessStepExceptionHandlingTest {

    @TempDir
    Path tempDir;

    // -----------------------------------------------------------------------
    // Path 3: exception does NOT match stopOnExceptions → crawl continues
    // -----------------------------------------------------------------------

    /**
     * When no stopOnExceptions configured, every pipeline exception triggers a
     * REJECTED_ERROR event and the crawl continues processing remaining refs.
     */
    @Test
    void fetcherThrows_noStopOnExceptions_crawlContinuesAndImportsRemaining()
            throws IOException {
        var throwRefs = List.of("ref-0", "ref-1");
        var allRefs = buildRefs(5); // ref-0..ref-4

        try (var harness = harness(allRefs, cfg -> {
            cfg.setId("test-no-stop");
            cfg.setNumThreads(1);
            cfg.setFetchers(List.of(Configurable.configure(
                    new MockFetcher(),
                    fcfg -> fcfg.setThrowOnAcceptRefs(throwRefs))));
        })) {
            var bag = harness.launchSync("node-1").getAllNodesEventNameBag();

            // Two refs throw → two REJECTED_ERROR events
            assertThat(bag.getCount(CrawlerEvent.REJECTED_ERROR)).isEqualTo(2);
            // Three refs succeed → three DOCUMENT_IMPORTED events
            assertThat(bag.getCount(CrawlerEvent.DOCUMENT_IMPORTED))
                    .isEqualTo(3);
        }
    }

    /**
     * When stopOnExceptions lists a type that does NOT match RuntimeException,
     * the exception is logged-and-ignored and the crawl continues for all refs.
     */
    @Test
    void fetcherThrows_nonMatchingStopOnExceptions_crawlContinues()
            throws IOException {
        var allRefs = buildRefs(4);

        try (var harness = harness(allRefs, cfg -> {
            cfg.setId("test-non-match");
            cfg.setNumThreads(1);
            // IOException does NOT match RuntimeException thrown by the fetcher
            cfg.setStopOnExceptions(List.of(java.io.IOException.class));
            cfg.setFetchers(List.of(Configurable.configure(
                    new MockFetcher(),
                    fcfg -> fcfg
                            .setThrowOnAcceptRefs(List.of("ref-0", "ref-2")))));
        })) {
            var bag = harness.launchSync("node-1").getAllNodesEventNameBag();

            assertThat(bag.getCount(CrawlerEvent.REJECTED_ERROR)).isEqualTo(2);
            assertThat(bag.getCount(CrawlerEvent.DOCUMENT_IMPORTED))
                    .isEqualTo(2);
        }
    }

    // -----------------------------------------------------------------------
    // Path 2: exception MATCHES stopOnExceptions → crawl stops
    // -----------------------------------------------------------------------

    /**
     * When stopOnExceptions includes RuntimeException (the exact type thrown),
     * the first pipeline error stops the crawler.  All refs throw, so zero
     * documents are ever successfully imported.
     */
    @Test
    void fetcherThrowsOnAll_matchingStopOnExceptions_crawlStopsEarly()
            throws IOException {
        try (var harness = harness(buildRefs(10), cfg -> {
            cfg.setId("test-stop-on-ex");
            cfg.setNumThreads(1); // single thread → deterministic
            cfg.setStopOnExceptions(List.of(RuntimeException.class));
            cfg.setFetchers(List.of(Configurable.configure(
                    new MockFetcher(),
                    fcfg -> fcfg.setThrowOnAccept(true))));
        })) {
            var bag = harness.launchSync("node-1").getAllNodesEventNameBag();

            // Crawler stopped → no documents imported
            assertThat(bag.getCount(CrawlerEvent.DOCUMENT_IMPORTED)).isZero();
            // At least one REJECTED_ERROR was fired before the stop
            assertThat(bag.getCount(CrawlerEvent.REJECTED_ERROR))
                    .isGreaterThanOrEqualTo(1);
        }
    }

    /**
     * stopOnExceptions with Exception (supertype of RuntimeException) also
     * triggers a stop on the first error.
     */
    @Test
    void fetcherThrowsOnFirst_superTypeStopOnExceptions_crawlStops()
            throws IOException {
        try (var harness = harness(buildRefs(5), cfg -> {
            cfg.setId("test-supertype-stop");
            cfg.setNumThreads(1);
            cfg.setStopOnExceptions(List.of(Exception.class));
            cfg.setFetchers(List.of(Configurable.configure(
                    new MockFetcher(),
                    fcfg -> fcfg.setThrowOnAccept(true))));
        })) {
            var bag = harness.launchSync("node-1").getAllNodesEventNameBag();

            assertThat(bag.getCount(CrawlerEvent.DOCUMENT_IMPORTED)).isZero();
            assertThat(bag.getCount(CrawlerEvent.REJECTED_ERROR))
                    .isGreaterThanOrEqualTo(1);
        }
    }

    // -----------------------------------------------------------------------
    // Path 3 edge-case: ALL docs throw, no stopOnExceptions → all REJECTED_ERROR
    // -----------------------------------------------------------------------

    /**
     * When every document throws in acceptRequest() and there are no
     * stopOnExceptions, the crawl runs to completion with every document
     * producing a REJECTED_ERROR event.
     */
    @Test
    void fetcherThrowsOnAll_noStopOnExceptions_allDocumentsRejected()
            throws IOException {
        int numRefs = 5;
        try (var harness = harness(buildRefs(numRefs), cfg -> {
            cfg.setId("test-all-rejected");
            cfg.setNumThreads(1);
            cfg.setFetchers(List.of(Configurable.configure(
                    new MockFetcher(),
                    fcfg -> fcfg.setThrowOnAccept(true))));
        })) {
            var bag = harness.launchSync("node-1").getAllNodesEventNameBag();

            assertThat(bag.getCount(CrawlerEvent.DOCUMENT_IMPORTED)).isZero();
            assertThat(bag.getCount(CrawlerEvent.REJECTED_ERROR))
                    .isEqualTo(numRefs);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private List<String> buildRefs(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> "ref-" + i)
                .toList();
    }

    private CrawlerTestHarness harness(
            List<String> startRefs,
            Consumer<CrawlerConfig> cfgOverride) {
        return new CrawlerTestHarness(new CrawlerTestInstrument()
                .setRecordEvents(true)
                .setConfigModifier(cfg -> {
                    cfg.setStartReferences(startRefs);
                    cfg.setIdleTimeout(Duration.ofMillis(500));
                    cfg.setMaxQueueBatchSize(10);
                    cfgOverride.accept(cfg);
                })
                .setWorkDir(tempDir)
                .setNewJvm(false)
                .setClustered(false));
    }
}
