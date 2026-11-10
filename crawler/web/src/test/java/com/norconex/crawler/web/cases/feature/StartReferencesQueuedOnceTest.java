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
package com.norconex.crawler.web.cases.feature;

import static com.norconex.crawler.web.mocks.MockWebsite.serverUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerSettings;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.crawler.core.doc.operations.filter.OnMatch;
import com.norconex.crawler.core.doc.operations.filter.impl.GenericReferenceFilter;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.test.CrawlerTestHarness;
import com.norconex.crawler.core.test.CrawlerTestInstrument;
import com.norconex.crawler.web.WebCrawlerConfig;
import com.norconex.crawler.web.WebCrawlerDriverFactory;
import com.norconex.crawler.web.WebTestUtil;
import com.norconex.crawler.web.mocks.MockWebsite;

/**
 * Start references go through the queue pipeline once per crawl. A start
 * reference rejected by a reference filter is a visible way to count it:
 * each pass through the queue pipeline fires one rejection event.
 */
@MockServerSettings
@Timeout(60)
class StartReferencesQueuedOnceTest {

    @Test
    void testStartReferencesQueuedOnce(
            ClientAndServer client, @TempDir Path tempDir) throws Exception {
        MockWebsite.whenBoundedDepth(client, 0);
        var accepted = serverUrl(client, "/queuedOnce/0000");
        var rejected = serverUrl(client, "/queuedOnce/rejected");

        var instrument = new CrawlerTestInstrument()
                .setDriverSupplierClass(WebCrawlerDriverFactory.class)
                .setRecordEvents(true)
                .setWorkDir(tempDir)
                .setNewJvm(false)
                .setClustered(false)
                .setConfigModifier(cfg -> {
                    var webCfg = (WebCrawlerConfig) cfg;
                    webCfg.setId("test-start-refs-queued-once");
                    webCfg.setStartReferences(List.of(accepted, rejected));
                    webCfg.setNumThreads(1);
                    webCfg.setMaxDepth(0);
                    webCfg.setDelayResolver(WebTestUtil.delayResolver(0));
                    webCfg.setReferenceFilters(List.of(Configurable.configure(
                            new GenericReferenceFilter(), c -> c
                                    .setValueMatcher(TextMatcher
                                            .wildcard("*/rejected"))
                                    .setOnMatch(OnMatch.EXCLUDE))));
                });

        try (var harness = new CrawlerTestHarness(instrument)) {
            var events = harness.launchSync("node1")
                    .getAllNodesEventNameBag();

            assertThat(events.getCount(CrawlerEvent.REJECTED_FILTER))
                    .as("the rejected start reference is queued once")
                    .isOne();
            assertThat(events.getCount(CrawlerEvent.DOCUMENT_IMPORTED))
                    .as("the accepted start reference is crawled once")
                    .isOne();
        }
    }
}
