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
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerSettings;
import org.mockserver.model.MediaType;

import com.norconex.commons.lang.event.Event;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.test.CrawlerTestHarness;
import com.norconex.crawler.core.test.CrawlerTestInstrument;
import com.norconex.crawler.web.WebCrawlerConfig;
import com.norconex.crawler.web.WebCrawlerDriverFactory;
import com.norconex.crawler.web.WebTestUtil;
import com.norconex.crawler.web.doc.operations.sitemap.impl.GenericSitemapResolver;

/**
 * Sitemap indices referring to each other are each read once, instead of
 * being followed endlessly.
 */
@MockServerSettings
@Timeout(60)
class SitemapIndexLoopTest {

    private static final String PATH = "/sitemapIndexLoop";
    private static final String INDEX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
            <sitemap><loc>%s</loc></sitemap>
            <sitemap><loc>%s</loc></sitemap>
            </sitemapindex>
            """;

    @Test
    void testIndicesReferringToEachOther(
            ClientAndServer client, @TempDir Path tempDir) throws Exception {
        var indexA = serverUrl(client, PATH + "/index-a.xml");
        var indexB = serverUrl(client, PATH + "/index-b.xml");
        var urlset = serverUrl(client, PATH + "/urlset.xml");
        var page1 = serverUrl(client, PATH + "/page-1.html");
        var page2 = serverUrl(client, PATH + "/page-2.html");

        // A lists B and the urlset; B lists A and the urlset.
        whenXml(client, "/index-a.xml", INDEX.formatted(indexB, urlset));
        whenXml(client, "/index-b.xml", INDEX.formatted(indexA, urlset));
        whenXml(client, "/urlset.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                <url><loc>%s</loc></url>
                <url><loc>%s</loc></url>
                </urlset>
                """.formatted(page1, page2));
        client.when(request(PATH + "/page-.*")).respond(response()
                .withBody("<html><body>A page.</body></html>",
                        MediaType.HTML_UTF_8));

        var imported = new CopyOnWriteArrayList<String>();
        var instrument = new CrawlerTestInstrument()
                .setDriverSupplierClass(WebCrawlerDriverFactory.class)
                .setRecordEvents(true)
                .setWorkDir(tempDir)
                .setNewJvm(false)
                .setClustered(false)
                .setConfigModifier(cfg -> {
                    var webCfg = (WebCrawlerConfig) cfg;
                    webCfg.setId("test-sitemap-index-loop");
                    webCfg.setStartReferencesSitemaps(List.of(indexA));
                    webCfg.setSitemapResolver(new GenericSitemapResolver());
                    webCfg.setNumThreads(1);
                    webCfg.setMaxDepth(0);
                    webCfg.setDelayResolver(WebTestUtil.delayResolver(0));
                    webCfg.addEventListener((Event event) -> {
                        if (event.is(CrawlerEvent.DOCUMENT_IMPORTED)
                                && event instanceof CrawlerEvent ce) {
                            imported.add(ce.getCrawlEntry().getReference());
                        }
                    });
                });

        try (var harness = new CrawlerTestHarness(instrument)) {
            harness.launchSync("node1");
        }

        assertThat(imported).containsExactlyInAnyOrder(page1, page2);
    }

    private static void whenXml(
            ClientAndServer client, String path, String body) {
        client.when(request(PATH + path))
                .respond(response().withBody(body, MediaType.XML_UTF_8));
    }
}
