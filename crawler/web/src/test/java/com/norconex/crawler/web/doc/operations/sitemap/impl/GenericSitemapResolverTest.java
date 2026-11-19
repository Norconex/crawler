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
package com.norconex.crawler.web.doc.operations.sitemap.impl;

import static com.norconex.crawler.web.mocks.MockWebsite.serverUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerSettings;
import org.mockserver.model.MediaType;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.web.WebCrawlerConfig;
import com.norconex.crawler.web.doc.operations.sitemap.SitemapContext;
import com.norconex.crawler.web.junit.WebCrawlingTest;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@MockServerSettings
@Timeout(30)
class GenericSitemapResolverTest {

    @WebCrawlingTest
    void testResolveSitemaps(
            ClientAndServer client, CrawlerContext ctx)
            throws IOException {

        // We test having a sitemap index file pointing to sitemap files, and
        // We test compression (MockServer compresses the body itself when
        // told the content encoding is gzip).
        // We test redirect

        client.when(request().withPath("/sitemap-index"))
                .respond(response().withBody("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <sitemapindex \
                            xmlns="https://www.sitemaps.org/schemas/sitemap/0.9">
                          <sitemap>
                            <loc>%s</loc>
                            <lastmod>2000-10-01T18:23:17+00:00</lastmod>
                          </sitemap>
                        </sitemapindex>
                        """.formatted(serverUrl(client, "sitemap")),
                        MediaType.XML_UTF_8));

        client.when(request().withPath("/sitemap"))
                .respond(response()
                        .withStatusCode(302)
                        .withHeader(
                                "Location",
                                serverUrl(client,
                                        "/sitemap-new")));

        client.when(request().withPath("/sitemap-new"))
                .respond(response()
                        .withHeader("Content-Encoding",
                                "gzip")
                        .withHeader("Content-type",
                                "text/xml; charset=utf-8")
                        .withBody(sitemapContent(
                                serverUrl(client,
                                        ""))));

        List<String> urls = new ArrayList<>();
        var resolver = ((WebCrawlerConfig) ctx.getCrawlConfig())
                .getSitemapResolver();

        var session = mock(CrawlerSession.class);
        ((GenericSitemapResolver) resolver).onCrawlerCrawlBegin(
                CrawlerEvent.builder()
                        .name("test")
                        .source(session)
                        .crawlSession(session)
                        .build());

        resolver.resolve(
                SitemapContext
                        .builder()
                        .fetcher(ctx.getFetcher())
                        .location(serverUrl(client,
                                "sitemap-index"))
                        .urlConsumer(rec -> urls.add(rec
                                .getReference()))
                        .build());

        assertThat(urls).containsExactly(
                serverUrl(client, "/pageA.html"),
                serverUrl(client, "/pageB.html"));
    }

    private String sitemapContent(String baseUrl) {
        var content = """
                <urlset>
                  <url>
                    <loc>%s</loc>
                    <lastmod>2021-02-26</lastmod>
                    <changefreq>daily</changefreq>
                    <priority>0.5</priority>
                  </url>
                  <url>
                    <loc>%s</loc>
                    <lastmod>2021-04-01</lastmod>
                    <changefreq>daily</changefreq>
                    <priority>1.0</priority>
                  </url>
                </urlset>
                """.formatted(
                baseUrl + "pageA.html",
                baseUrl + "pageB.html");
        return content;
    }

    @Test
    void testWriteRead() {
        var r = new GenericSitemapResolver();
        r.getConfiguration().setLenient(true);
        LOG.debug("Writing/Reading this: {}", r);
        assertThatNoException().isThrownBy(
                () -> BeanMapper.DEFAULT.assertWriteRead(r));
    }
}
