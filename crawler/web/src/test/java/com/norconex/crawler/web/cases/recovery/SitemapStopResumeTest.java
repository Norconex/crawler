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
package com.norconex.crawler.web.cases.recovery;

import static com.norconex.crawler.web.mocks.MockWebsite.serverUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerSettings;
import org.mockserver.model.MediaType;

import com.norconex.commons.lang.event.Event;
import com.norconex.commons.lang.event.EventListener;
import com.norconex.crawler.core.Crawler;
import com.norconex.crawler.core.doc.operations.filter.ReferenceFilter;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.test.CrawlerTestHarness;
import com.norconex.crawler.core.test.CrawlerTestInstrument;
import com.norconex.crawler.web.WebCrawlerConfig;
import com.norconex.crawler.web.WebCrawlerDriverFactory;
import com.norconex.crawler.web.WebTestUtil;
import com.norconex.crawler.web.doc.operations.sitemap.impl.GenericSitemapResolver;

/**
 * A crawl stopped while reading its start sitemap resumes reading it: across
 * the stopped run and the resumed one, every page of the sitemap is crawled,
 * and none twice. The sitemap must not be skipped on resume as if it had
 * been fully read.
 *
 * <p>The stop lands at an exact point: a reference filter, invoked as each
 * sitemap URL is queued, holds the queueing after {@value #STOP_AFTER} URLs
 * until the stop has been sent.</p>
 */
@MockServerSettings
@Timeout(120)
class SitemapStopResumeTest {

    private static final String PATH = "/sitemapStopResume";
    private static final int PAGE_COUNT = 10;
    private static final int STOP_AFTER = 5;
    private static final long FAILSAFE_SECONDS = 60;

    @ParameterizedTest(name = "async={0}")
    @ValueSource(booleans = { false, true })
    void testSitemapReadingResumes(boolean async, ClientAndServer client,
            @TempDir Path tempDir) throws Exception {
        var pages = IntStream.range(0, PAGE_COUNT)
                .mapToObj(i -> serverUrl(client, PATH + "/page-" + i + ".html"))
                .toList();
        whenSitemapAndPages(client, pages);

        var holder = new HoldingFilter(pages.get(STOP_AFTER - 1));
        var imported = new CopyOnWriteArrayList<String>();
        var instrument = new CrawlerTestInstrument()
                .setDriverSupplierClass(WebCrawlerDriverFactory.class)
                .setRecordEvents(true)
                .setWorkDir(tempDir)
                .setNewJvm(false)
                .setClustered(false)
                .setConfigModifier(cfg -> {
                    var webCfg = (WebCrawlerConfig) cfg;
                    webCfg.setId("test-sitemap-stop-resume");
                    webCfg.setStartReferencesSitemaps(List.of(
                            serverUrl(client, PATH + "/sitemap.xml")));
                    webCfg.setSitemapResolver(new GenericSitemapResolver());
                    webCfg.setStartReferencesAsync(async);
                    webCfg.setNumThreads(1);
                    webCfg.setMaxDepth(0);
                    webCfg.setDelayResolver(WebTestUtil.delayResolver(0));
                    webCfg.setReferenceFilters(List.of(holder));
                    webCfg.addEventListener(new ImportRecorder(imported));
                });

        try (var harness = new CrawlerTestHarness(instrument)) {
            //--- First run, stopped while reading the sitemap ---
            var firstRun = harness.launchAsync("node1");
            assertThat(holder.held.await(FAILSAFE_SECONDS, TimeUnit.SECONDS))
                    .as("sitemap reading reached the stop point")
                    .isTrue();
            new Crawler(WebCrawlerDriverFactory.create(),
                    harness.getFirstNodeConfig()).stop();
            holder.stopSent.countDown();
            firstRun.get(FAILSAFE_SECONDS, TimeUnit.SECONDS);
            assertThat(new ArrayList<>(imported))
                    .as("the first run stops before the sitemap is read")
                    .hasSizeLessThan(PAGE_COUNT);

            //--- Second run, resuming ---
            harness.launchSync("node1");
        }

        assertThat(imported)
                .as("every sitemap page crawled exactly once overall")
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(pages);
    }

    private static void whenSitemapAndPages(
            ClientAndServer client, List<String> pages) {
        var urls = new StringBuilder();
        for (var page : pages) {
            urls.append("<url><loc>%s</loc>".formatted(page)
                    + "<lastmod>2005-01-01</lastmod></url>\n");
        }
        // Unchanged between runs, as is typical: the resumed crawl must
        // still read it rather than skip it as unmodified.
        client.when(request(PATH + "/sitemap.xml")).respond(response()
                .withHeader("Last-Modified", "Wed, 01 Jan 2025 00:00:00 GMT")
                .withBody("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                        %s</urlset>
                        """.formatted(urls), MediaType.XML_UTF_8));
        client.when(request(PATH + "/page-.*")).respond(response()
                .withBody("<html><body>A page.</body></html>",
                        MediaType.HTML_UTF_8));
    }

    /**
     * Accepts every reference. The first time it sees the given one (as it
     * is queued from the sitemap), it holds until the stop was sent.
     */
    static class HoldingFilter implements ReferenceFilter {
        private final String holdAt;
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch stopSent = new CountDownLatch(1);

        HoldingFilter(String holdAt) {
            this.holdAt = holdAt;
        }

        @Override
        public boolean acceptReference(String reference) {
            if (holdAt.equals(reference) && held.getCount() > 0) {
                held.countDown();
                try {
                    stopSent.await(FAILSAFE_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return true;
        }
    }

    static class ImportRecorder implements EventListener<Event> {
        private final List<String> imported;

        ImportRecorder(List<String> imported) {
            this.imported = imported;
        }

        @Override
        public void accept(Event event) {
            if (event.is(CrawlerEvent.DOCUMENT_IMPORTED)
                    && event instanceof CrawlerEvent crawlerEvent) {
                imported.add(crawlerEvent.getCrawlEntry().getReference());
            }
        }
    }
}
