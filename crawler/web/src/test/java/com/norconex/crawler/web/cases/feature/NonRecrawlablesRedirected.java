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
package com.norconex.crawler.web.cases.feature;

import static com.norconex.crawler.web.mocks.MockWebsite.serverUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.util.List;

import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerSettings;
import org.mockserver.model.MediaType;

import com.norconex.committer.core.CommitterException;
import com.norconex.crawler.core.CrawlerConfig.OrphansStrategy;
import com.norconex.crawler.web.WebCrawlerConfig;
import com.norconex.crawler.web.junit.WebCrawlingTest;
import com.norconex.crawler.web.junit.WebCrawlingTestCapturer;
import com.norconex.crawler.web.mocks.MockWebsite;

// Test for https://github.com/Norconex/crawler/issues/1121
// Redirected URL targets should not be orphaned if not ready to be recrawled
@MockServerSettings
public class NonRecrawlablesRedirected {

    private final String sitemapPath = "/sitemap.xml";
    private final String page1Path = "/page1.html";
    private final String page2Path = "/page2.html";
    private final String page3Path = "/page3.html";
    private final String page4Path = "/page4.html";
    private final String page400Path = "/page400.html";

    private static final String SITEMAP_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE xml>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
               <url>
                  <loc>%s</loc>
                  <lastmod>2005-01-01</lastmod>
               </url>
               <url>
                  <loc>%s</loc>
                  <lastmod>2005-01-01</lastmod>
               </url>
               <url>
                  <loc>%s</loc>
                  <lastmod>2005-01-01</lastmod>
               </url>
               <url>
                  <loc>%s</loc>
                  <lastmod>2005-01-01</lastmod>
               </url>
               <url>
                  <loc>%s</loc>
                  <lastmod>2005-01-01</lastmod>
               </url>
            </urlset>
            """;

    @WebCrawlingTest
    void testNonRecrawlablesRedirected(
            ClientAndServer client, WebCrawlerConfig cfg)
            throws CommitterException {
        cfg.setStartReferencesSitemaps(List.of(serverUrl(client, sitemapPath)));
        cfg.setOrphansStrategy(OrphansStrategy.DELETE);
        cfg.setDocumentChecksummer(null);
        cfg.setMetadataChecksummer(null);

        mockServer(client);

        // Run #1
        var mem = WebCrawlingTestCapturer.crawlAndCapture(cfg).getCommitter();
        assertThat(mem.getUpsertCount()).isEqualTo(4);
        assertThat(mem.getDeleteCount()).isZero();
        mem.clean();

        // Run #2
        mem = WebCrawlingTestCapturer.crawlAndCapture(cfg).getCommitter();
        assertThat(mem.getUpsertCount()).isEqualTo(3);
        assertThat(mem.getDeleteCount()).isZero();
        mem.clean();

        // Run #3
        mem = WebCrawlingTestCapturer.crawlAndCapture(cfg).getCommitter();
        assertThat(mem.getUpsertCount()).isEqualTo(3);
        assertThat(mem.getDeleteCount()).isZero();
        mem.clean();

        // Run #4
        mem = WebCrawlingTestCapturer.crawlAndCapture(cfg).getCommitter();
        assertThat(mem.getUpsertCount()).isEqualTo(3);
        assertThat(mem.getDeleteCount()).isZero();
        mem.clean();
    }

    private void mockServer(ClientAndServer client) {
        client.reset();

        // Sitemap
        client.when(request(sitemapPath))
                .respond(response().withBody(SITEMAP_XML.formatted(
                        serverUrl(client, page1Path),
                        serverUrl(client, page2Path),
                        serverUrl(client, page3Path),
                        serverUrl(client, page4Path),
                        serverUrl(client, page400Path)),
                        MediaType.XML_UTF_8));

        // The links in ALL following pages should not be followed when
        // sitemap is present and stayOnSitemap is true.

        var pathRegex = ".*page(\\d+).html$";
        client.when(
                request().withPath(pathRegex))
                .respond(request -> {
                    // Capture the matched group from the URL
                    var pageNo = Integer.parseInt(request
                            .getPath()
                            .getValue()
                            .replaceFirst(pathRegex, "$1"));
                    if (pageNo <= 4) {
                        return MockWebsite.redirectResponse(serverUrl(client,
                                "/page%s.html".formatted(pageNo * 100)));
                    }
                    return MockWebsite.htmlResponseWithBody("""
                            <h1>Page %1$s</h1>
                            <p>This is page %1$s.</p>
                            """.formatted(pageNo));
                });
    }
}
