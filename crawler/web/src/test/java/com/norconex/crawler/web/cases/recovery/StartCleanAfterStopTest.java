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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerSettings;

import com.norconex.commons.lang.event.Event;
import com.norconex.commons.lang.event.EventListener;
import com.norconex.crawler.core.Crawler;
import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.test.CrawlerTestHarness;
import com.norconex.crawler.core.test.CrawlerTestInstrument;
import com.norconex.crawler.web.WebCrawlerConfig;
import com.norconex.crawler.web.WebCrawlerDriverFactory;
import com.norconex.crawler.web.WebTestUtil;
import com.norconex.crawler.web.mocks.MockWebsite;

/**
 * A crawl stopped part way, then cleaned, must start again from scratch:
 * crawl the start reference again and get its full document budget, rather
 * than resume the stopped crawl or find nothing to do.
 *
 * <p>Nothing here depends on timing. The crawl is stopped at an exact
 * document: the crawl thread is held right after that document is imported
 * until the stop has been sent, so it cannot process another one. Both runs
 * are therefore exactly predictable, whatever the machine load.</p>
 */
@MockServerSettings
@Timeout(120)
class StartCleanAfterStopTest {

    private static final String PATH = "/startCleanAfterStop";
    /** Pages in the mock site: 0000 to SITE_DEPTH, linked one to the next. */
    private static final int SITE_DEPTH = 12;
    private static final int MAX_DOCS = 12;
    /** The first run is stopped after this many imported documents. */
    private static final int STOP_AFTER = 3;
    /** Only bounds a hang. Never reached by a working crawler. */
    private static final long FAILSAFE_SECONDS = 60;

    @Test
    void testStartCleanAfterStop(ClientAndServer client, @TempDir Path tempDir)
            throws Exception {
        MockWebsite.whenBoundedDepth(client, SITE_DEPTH);
        var startUrl = serverUrl(client, PATH + "/0000");

        var firstRun = new StopAtDocument(STOP_AFTER);
        var instrument = new CrawlerTestInstrument()
                .setDriverSupplierClass(WebCrawlerDriverFactory.class)
                .setRecordEvents(true)
                .setWorkDir(tempDir)
                .setNewJvm(false)
                .setClustered(false)
                .setConfigModifier(cfg -> configure(cfg, startUrl, firstRun));

        try (var harness = new CrawlerTestHarness(instrument)) {

            //--- First run, stopped after STOP_AFTER documents ---
            var futureResult = harness.launchAsync("node1");
            assertThat(firstRun.stopPointReached.await(
                    FAILSAFE_SECONDS, TimeUnit.SECONDS))
                            .as("first run reached the stop point")
                            .isTrue();
            // Returns once the crawler has set its stop flag: the admin
            // endpoint only responds after the stop is in effect.
            new Crawler(WebCrawlerDriverFactory.create(),
                    harness.getFirstNodeConfig()).stop();
            firstRun.stopSent.countDown();
            futureResult.get(FAILSAFE_SECONDS, TimeUnit.SECONDS);

            assertThat(firstRun.importedRefs)
                    .as("first run imports exactly up to the stop point")
                    .hasSize(STOP_AFTER)
                    .startsWith(startUrl);

            //--- Clean, then start again ---
            new Crawler(WebCrawlerDriverFactory.create(),
                    harness.getFirstNodeConfig()).clean();

            var secondRun = new StopAtDocument(-1); // records only
            instrument.setConfigModifier(
                    cfg -> configure(cfg, startUrl, secondRun));
            harness.launchSync("node1");

            // A resumed crawl would continue after the stop point with what
            // is left of the budget; a crawl that wrongly believes it resumes
            // after the clean would import nothing.
            assertThat(secondRun.importedRefs)
                    .as("after a clean, the crawl starts from scratch")
                    .hasSize(MAX_DOCS)
                    .startsWith(startUrl);
        }
    }

    private static void configure(
            CrawlerConfig cfg, String startUrl, StopAtDocument listener) {
        var webCfg = (WebCrawlerConfig) cfg;
        webCfg.setId("test-startclean-after-stop");
        webCfg.setStartReferences(List.of(startUrl));
        // One thread: documents are imported one at a time, in link order.
        webCfg.setNumThreads(1);
        webCfg.setMaxDepth(SITE_DEPTH);
        // Lower than the number of pages, so a fresh crawl is bounded by
        // this limit and imports exactly MAX_DOCS documents.
        webCfg.setMaxDocuments(MAX_DOCS);
        // No politeness delay: nothing here relies on the crawl being slow.
        webCfg.setDelayResolver(WebTestUtil.delayResolver(0));
        webCfg.setMetadataChecksummer(null);
        webCfg.setDocumentChecksummer(null);
        webCfg.addEventListener(listener);
    }

    /**
     * Records imported documents and, when given a stop point, holds the
     * crawl thread right after that document until the test has sent the
     * stop. Listeners are invoked on the thread firing the event, and the
     * crawl checks for a stop between documents, so no other document can be
     * processed in the meantime.
     */
    static class StopAtDocument implements EventListener<Event> {
        private final int stopAfter;
        private final List<String> importedRefs = new CopyOnWriteArrayList<>();
        private final CountDownLatch stopPointReached = new CountDownLatch(1);
        private final CountDownLatch stopSent = new CountDownLatch(1);

        StopAtDocument(int stopAfter) {
            this.stopAfter = stopAfter;
        }

        @Override
        public void accept(Event event) {
            if (event.is(CrawlerEvent.DOCUMENT_IMPORTED)
                    && event instanceof CrawlerEvent crawlerEvent) {
                importedRefs.add(crawlerEvent.getCrawlEntry().getReference());
                if (importedRefs.size() == stopAfter) {
                    stopPointReached.countDown();
                    awaitStopSent();
                }
            }
        }

        private void awaitStopSent() {
            try {
                stopSent.await(FAILSAFE_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
