/* Copyright 2019-2026 Norconex Inc.
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
package com.norconex.crawler.web.cases.recovery;

import static com.norconex.crawler.web.mocks.MockWebsite.serverUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerSettings;

import com.norconex.crawler.core.Crawler;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.test.CrawlerTestHarness;
import com.norconex.crawler.core.test.CrawlerTestInstrument;
import com.norconex.crawler.web.WebCrawlerDriverFactory;
import com.norconex.crawler.web.WebCrawlerConfig;
import com.norconex.crawler.web.WebTestUtil;
import com.norconex.crawler.web.mocks.MockWebsite;

import lombok.extern.slf4j.Slf4j;

/**
 * Test that the right amount of docs are crawled after stopping
 * the crawler and restarting it fresh (clean start).
 */
@MockServerSettings
@Slf4j
@Timeout(120)
class StartCleanAfterStopTest {

    private static final int MAX_DOCS = 12;
    private static final int SITE_DEPTH = 12;

    @Test
    void testStartCleanAfterStop(ClientAndServer client,
            @TempDir Path tempDir)
            throws Exception {
        var path = "/startCleanAfterStop";

        MockWebsite.whenBoundedDepth(client, SITE_DEPTH);

        var instrument = new CrawlerTestInstrument()
                .setDriverSupplierClass(
                        WebCrawlerDriverFactory.class)
                .setRecordEvents(true)
                .setWorkDir(tempDir)
                .setNewJvm(false)
                .setClustered(false)
                .setConfigModifier(cfg -> {
                    var webCfg = (WebCrawlerConfig) cfg;
                    webCfg.setId("test-startclean-after-stop");
                    webCfg.setStartReferences(
                            List.of(serverUrl(
                                    client,
                                    path + "/0000")));
                    webCfg.setNumThreads(1);
                    // Must stay >= MAX_DOCS so MaxDocuments — not the mock
                    // site's linear page chain — is the binding limit. The
                    // site only has SITE_DEPTH+1 pages; a lower cap here
                    // makes both this run and the second run converge on the
                    // exact same finite page set, so a first run that
                    // happens to finish before the stop signal lands (e.g.
                    // under CI's parallel-test CPU contention) leaves zero
                    // headroom for the second run to show progress beyond,
                    // failing the assertion at the bottom of this test with
                    // no actual crawler bug involved.
                    webCfg.setMaxDepth(SITE_DEPTH);
                    webCfg.setMaxDocuments(MAX_DOCS);
                    // The stop() call below is an out-of-process HTTP round
                    // trip through the crawler's admin server, not an
                    // in-memory signal — its delivery latency depends on OS
                    // thread scheduling. Under CI's parallel-tests profile
                    // (many JUnit classes competing for CPU), that latency
                    // can exceed what a short per-document delay leaves as
                    // headroom before the crawl finishes all MAX_DOCS
                    // documents on its own, which races the stop signal and
                    // makes the assertions below flaky depending on which
                    // one lands first. A generous per-document delay here
                    // keeps the crawl slow enough that the stop reliably
                    // arrives well before MAX_DOCS is reached.
                    webCfg.setDelayResolver(WebTestUtil
                            .delayResolver(1000));
                    webCfg.setMetadataChecksummer(null);
                    webCfg.setDocumentChecksummer(null);
                });

        try (var harness = new CrawlerTestHarness(instrument)) {

            //--- First run (will be stopped mid-crawl) ---
            var futureResult = harness.launchAsync("node1");
            harness.waitFor(Duration.ofSeconds(12))
                    .anyNodeToHaveFired(
                            CrawlerEvent.DOCUMENT_IMPORTED);
            new Crawler(WebCrawlerDriverFactory.create(),
                    harness.getFirstNodeConfig()).stop();
            var firstResult =
                    futureResult.get(40, TimeUnit.SECONDS);
            var firstTotalCount = firstResult
                    .getAllNodesEventNameBag()
                    .getCount(CrawlerEvent.DOCUMENT_IMPORTED);
            assertThat(firstTotalCount).isBetween(1, MAX_DOCS - 1);

            //--- Clean previous crawl stores ---
            new Crawler(WebCrawlerDriverFactory.create(),
                    harness.getFirstNodeConfig()).clean();

            //--- Fresh start (clean state, should process MAX_DOCS from scratch) ---
            instrument.setConfigModifier(cfg -> {
                var webCfg = (WebCrawlerConfig) cfg;
                webCfg.setId("test-startclean-after-stop");
                webCfg.setStartReferences(
                        List.of(serverUrl(client, path
                                + "/0000")));
                webCfg.setNumThreads(1);
                webCfg.setMaxDepth(SITE_DEPTH);
                webCfg.setMaxDocuments(MAX_DOCS);
                webCfg.setDelayResolver(
                        WebTestUtil.delayResolver(0));
                webCfg.setMetadataChecksummer(null);
                webCfg.setDocumentChecksummer(null);
            });
            var secondResult = harness.launchSync("node1");
            var secondTotalCount = secondResult
                    .getAllNodesEventNameBag()
                    .getCount(CrawlerEvent.DOCUMENT_IMPORTED);
            var secondRunCount = secondTotalCount - firstTotalCount;
            assertThat(secondRunCount).isGreaterThan(0);
        }
    }
}
