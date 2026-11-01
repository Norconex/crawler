/* Copyright 2023-2026 Norconex Inc.
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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.file.ContentType;
import com.norconex.crawler.web.ledger.WebCrawlerEntry;
import com.norconex.crawler.web.stubs.CrawlDocStubs;

@Timeout(30)
@SuppressWarnings("null")
class SitemapParserTest {

    @Test
    void testParse() throws IOException {
        List<WebCrawlerEntry> extractedLinks = new ArrayList<>();
        var p = new SitemapParser(false, new AtomicBoolean(false));

        try (var is = getClass().getResourceAsStream("sitemap.xml")) {

            var childSitemaps = p.parse(
                    CrawlDocStubs.crawlDoc(
                            "https://example.com/index.html",
                            ContentType.XML,
                            is),
                    d -> {
                        extractedLinks.add(d);
                    });
            assertThat(childSitemaps).isEmpty();
        }

        // All links there?
        Assertions.assertEquals(
                Arrays.asList(
                        "https://example.com/linkA",
                        "https://example.com/linkB",
                        "https://example.com/linkC",
                        "https://example.com/linkD"),
                extractedLinks.stream()
                        .map(WebCrawlerEntry::getReference)
                        .collect(Collectors.toList()));

        // test second one:
        var doc = extractedLinks.get(1);
        Assertions.assertEquals(
                "https://example.com/linkB",
                doc.getReference());
        Assertions.assertEquals(
                "2021-04-01",
                doc.getSitemapLastMod().toLocalDate()
                        .toString());
        Assertions.assertEquals("daily", doc.getSitemapChangeFreq());
        Assertions.assertEquals(1f, doc.getSitemapPriority());
    }

    @Test
    void testStoppingFlagPreventsFullParse() throws IOException {
        var stopping = new AtomicBoolean(true);
        var p = new SitemapParser(false, stopping);
        List<WebCrawlerEntry> links = new ArrayList<>();

        try (var is = getClass().getResourceAsStream("sitemap.xml")) {
            var childSitemaps = p.parse(
                    CrawlDocStubs.crawlDoc(
                            "https://example.com/index.html",
                            ContentType.XML,
                            is),
                    links::add);
            // stopping=true means no URLs should be processed
            assertThat(links).isEmpty();
            assertThat(childSitemaps).isEmpty();
        }
    }

    @Test
    void testSitemapindexReturnsChildSitemaps() throws IOException {
        var xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <sitemap>
                    <loc>https://example.com/sitemap1.xml</loc>
                  </sitemap>
                  <sitemap>
                    <loc>https://example.com/sitemap2.xml</loc>
                  </sitemap>
                </sitemapindex>
                """;
        var p = new SitemapParser(false, new AtomicBoolean(false));
        List<WebCrawlerEntry> links = new ArrayList<>();
        var is = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        var children = p.parse(
                CrawlDocStubs.crawlDoc(
                        "https://example.com/sitemap.xml",
                        ContentType.XML,
                        is),
                links::add);

        assertThat(children).hasSize(2);
        assertThat(children.get(0).getLocation())
                .isEqualTo("https://example.com/sitemap1.xml");
        assertThat(links).isEmpty();
    }

    @Test
    void testLenientMode_urlOutsideLocationDirIncluded() {
        var xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset>
                  <url>
                    <loc>https://other.com/page</loc>
                  </url>
                </urlset>
                """;
        var p = new SitemapParser(true, new AtomicBoolean(false));
        List<WebCrawlerEntry> links = new ArrayList<>();
        var is = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        p.parse(
                CrawlDocStubs.crawlDoc(
                        "https://example.com/sitemap.xml",
                        ContentType.XML,
                        is),
                links::add);

        assertThat(links).hasSize(1);
        assertThat(links.get(0).getReference())
                .isEqualTo("https://other.com/page");
    }

    @Test
    void testStrictMode_urlOutsideLocationDirExcluded() {
        var xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset>
                  <url>
                    <loc>https://other.com/page</loc>
                  </url>
                </urlset>
                """;
        var p = new SitemapParser(false, new AtomicBoolean(false));
        List<WebCrawlerEntry> links = new ArrayList<>();
        var is = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        p.parse(
                CrawlDocStubs.crawlDoc(
                        "https://example.com/sitemap.xml",
                        ContentType.XML,
                        is),
                links::add);

        assertThat(links).isEmpty();
    }

    @Test
    void testInvalidPriorityIsSkippedGracefully() {
        var xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset>
                  <url>
                    <loc>https://example.com/page</loc>
                    <priority>not-a-number</priority>
                  </url>
                </urlset>
                """;
        var p = new SitemapParser(false, new AtomicBoolean(false));
        List<WebCrawlerEntry> links = new ArrayList<>();
        var is = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        p.parse(
                CrawlDocStubs.crawlDoc(
                        "https://example.com/sitemap.xml",
                        ContentType.XML,
                        is),
                links::add);

        assertThat(links).hasSize(1);
        assertThat(links.get(0).getSitemapPriority()).isZero();
    }

    @Test
    void testBlankUrlInEntryIsSkipped() {
        var xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset>
                  <url>
                    <loc>   </loc>
                  </url>
                </urlset>
                """;
        var p = new SitemapParser(false, new AtomicBoolean(false));
        List<WebCrawlerEntry> links = new ArrayList<>();
        var is = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        p.parse(
                CrawlDocStubs.crawlDoc(
                        "https://example.com/sitemap.xml",
                        ContentType.XML,
                        is),
                links::add);

        assertThat(links).isEmpty();
    }

    @Test
    void testInvalidXmlLogsErrorAndReturnsEmpty() {
        var notXml = "this is not xml at all <<<";
        var p = new SitemapParser(false, new AtomicBoolean(false));
        List<WebCrawlerEntry> links = new ArrayList<>();
        var is = new ByteArrayInputStream(
                notXml.getBytes(StandardCharsets.UTF_8));
        var children = p.parse(
                CrawlDocStubs.crawlDoc(
                        "https://example.com/sitemap.xml",
                        ContentType.XML,
                        is),
                links::add);

        assertThat(links).isEmpty();
        assertThat(children).isEmpty();
    }

    @Test
    void testSitemapChildWithBlankLocIsSkipped() {
        var xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <sitemapindex>
                  <sitemap>
                    <loc></loc>
                  </sitemap>
                </sitemapindex>
                """;
        var p = new SitemapParser(false, new AtomicBoolean(false));
        var is = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        var children = p.parse(
                CrawlDocStubs.crawlDoc(
                        "https://example.com/sitemap.xml",
                        ContentType.XML,
                        is),
                e -> {});

        assertThat(children).isEmpty();
    }
}
