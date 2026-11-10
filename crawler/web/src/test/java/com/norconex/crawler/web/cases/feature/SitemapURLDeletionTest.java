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
package com.norconex.crawler.web.cases.feature;

import static com.norconex.crawler.web.mocks.MockWebsite.serverUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.MediaType.HTML_UTF_8;

import java.util.List;

import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerSettings;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;

import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.crawler.core.CrawlerConfig.OrphansStrategy;
import com.norconex.crawler.web.WebCrawlerConfig;
import com.norconex.crawler.web.doc.operations.sitemap.impl.GenericSitemapResolver;
import com.norconex.crawler.web.junit.WebCrawlingTest;
import com.norconex.crawler.web.junit.WebCrawlingTestCapturer;
import org.junit.jupiter.api.Timeout;

/**
 * The second time the sitemap has 1 less URL and that URL no longer
 * exists.
 */
//Test for https://github.com/Norconex/collector-http/issues/390
@MockServerSettings
@Timeout(60) // several crawls: hang guard with room for loaded CI
@SuppressWarnings("null")
class SitemapURLDeletionTest {

    private final String sitemapPath = "/sitemapUrlDeletion/sitemap.xml";
    private final String page1Path = "/sitemapUrlDeletion/page1.html";
    private final String page2Path = "/sitemapUrlDeletion/page2.html";
    private final String page3Path = "/sitemapUrlDeletion/page3.html";
    private final String page33Path = "/sitemapUrlDeletion/page33.html";

    private static final String SITEMAP_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE xml>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
               <url>
                  <loc>%s</loc>
                  <lastmod>2005-01-01</lastmod>
                  <changefreq>monthly</changefreq>
                  <priority>0.8</priority>
               </url>
               <url>
                  <loc>%s</loc>
                  <lastmod>2005-01-01</lastmod>
                  <changefreq>monthly</changefreq>
                  <priority>0.8</priority>
               </url>
               <url>
                  <loc>%s</loc>
                  <lastmod>2005-01-01</lastmod>
                  <changefreq>monthly</changefreq>
                  <priority>0.8</priority>
               </url>
            </urlset>
            """;

    @WebCrawlingTest(config = """
            recrawlableResolver:
              class: GenericRecrawlableResolver
              sitemapSupport: NEVER
            """)
    void testSitemapURLDeletion(ClientAndServer client,
            WebCrawlerConfig cfg)
            throws CommitterException {

        cfg.setSitemapResolver(new GenericSitemapResolver())
                .setStartReferencesSitemaps(
                        List.of(serverUrl(client,
                                sitemapPath)))
                .setOrphansStrategy(OrphansStrategy.PROCESS);

        // First time, 3 upserts and 0 deletes
        whenSitemap(client, true);
        var mem = WebCrawlingTestCapturer.crawlAndCapture(cfg)
                .getCommitter();
        assertThat(mem.getUpsertRequests())
                .map(UpsertRequest::getReference)
                .containsExactlyInAnyOrder(
                        serverUrl(client, page1Path),
                        serverUrl(client, page2Path),
                        serverUrl(client, page3Path));
        assertThat(mem.getDeleteCount()).isZero();
        mem.clean();

        // Second time, 1 add and 1 delete (2 unmodified)
        whenSitemap(client, false);
        mem = WebCrawlingTestCapturer.crawlAndCapture(cfg)
                .getCommitter();
        assertThat(mem.getUpsertRequests())
                .map(UpsertRequest::getReference)
                .containsExactlyInAnyOrder(
                        serverUrl(client, page33Path));
        assertThat(mem.getDeleteRequests())
                .map(DeleteRequest::getReference)
                .containsExactlyInAnyOrder(
                        serverUrl(client, page3Path));
        mem.clean();
    }

    // The URL that is 404 should not be deleted if marked not ready for
    // recrawl.
    @WebCrawlingTest
    void testSitemapURLDeletionNotReadyForRecrawl(
            ClientAndServer client, WebCrawlerConfig cfg)
            throws CommitterException {

        cfg.setSitemapResolver(new GenericSitemapResolver())
                .setStartReferencesSitemaps(
                        List.of(serverUrl(client,
                                sitemapPath)))
                .setOrphansStrategy(OrphansStrategy.PROCESS);

        // First time, 3 upserts and 0 deletes
        whenSitemap(client, true);
        var mem = WebCrawlingTestCapturer.crawlAndCapture(cfg)
                .getCommitter();
        assertThat(mem.getUpsertRequests())
                .map(UpsertRequest::getReference)
                .containsExactlyInAnyOrder(
                        serverUrl(client, page1Path),
                        serverUrl(client, page2Path),
                        serverUrl(client, page3Path));
        assertThat(mem.getDeleteCount()).isZero();
        mem.clean();

        // Second time, 1 add and 0 delete (not ready for recrawl)
        whenSitemap(client, false);
        mem = WebCrawlingTestCapturer.crawlAndCapture(cfg)
                .getCommitter();
        assertThat(mem.getUpsertRequests())
                .map(UpsertRequest::getReference)
                .containsExactlyInAnyOrder(
                        serverUrl(client, page33Path));
        assertThat(mem.getDeleteRequests()).isEmpty();
        mem.clean();
    }

    private void whenSitemap(ClientAndServer client, boolean firstTime) {
        // @formatter:off
        client.reset();
        client
            .when(request(sitemapPath))
            .respond(response()
                .withBody(
                    SITEMAP_XML.formatted(
                            serverUrl(client, page1Path),
                            serverUrl(client, page2Path),
                            serverUrl(
                                    client,
                                    firstTime ? page3Path : page33Path)),
                    MediaType.XML_UTF_8));
        client
            .when(request(page1Path))
            .respond(response()
                .withBody("Page 1 always there.", HTML_UTF_8));
        client
            .when(request(page2Path))
            .respond(response().withBody("Page 2 always there.", HTML_UTF_8));
        if (firstTime) {
            client
                .when(request(page3Path))
                .respond(response()
                    .withBody("Page 3 there first time only.", HTML_UTF_8));
        } else {
            client
                .when(request(page3Path))
                .respond(HttpResponse.notFoundResponse());
            client
                .when(request(page33Path))
                .respond(response()
                    .withBody("Page 33 there second time only.", HTML_UTF_8));
        }
        // @formatter:on
    }
}
