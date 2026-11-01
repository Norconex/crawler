/* Copyright 2017-2026 Norconex Inc.
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
package com.norconex.crawler.web.doc.operations.link.impl;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.input.NullInputStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.bean.BeanMapper.Format;
import com.norconex.commons.lang.file.ContentType;
import com.norconex.commons.lang.map.PropertyMatcher;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.crawler.web.doc.operations.link.Link;
import com.norconex.crawler.web.doc.operations.link.impl.RegexLinkExtractorConfig.ExtractionPattern;
import com.norconex.crawler.web.stubs.CrawlDocStubs;
import com.norconex.importer.doc.Doc;

@Timeout(30)
@SuppressWarnings("null")
class RegexLinkExtractorTest {

    //TODO add a post import test for PDF with links.
    //TODO add a post import test for grabbing a URL value from a field.

    @Test
    void testLinkExtraction() throws IOException {
        var baseURL = "http://www.example.com/";
        var baseDir = baseURL + "test/";
        var docURL = baseDir + "RegexLinkExtractorTest.html";

        var extractor = new RegexLinkExtractor();
        extractor.getConfiguration()
                .setPatterns(
                        List.of(
                                new ExtractionPattern(
                                        "\\[\\s*(.*?)\\s*\\]",
                                        "$1"),
                                new ExtractionPattern(
                                        "<link>\\s*(.*?)\\s*</link>",
                                        "$1"),
                                new ExtractionPattern(
                                        "<a href=\"javascript:;\"[^>]*?id=\"p_(\\d+)\">",
                                        "/page?id=$1")));

        // All these must be found
        String[] expectedURLs = {
                baseURL + "page1.html",
                baseURL + "page2.html",
                baseURL + "page3.html",
                baseURL + "page4.html",
                baseDir + "page5.html",
                baseURL + "page?id=12345",
                baseURL + "page?id=67890",
        };
        var is = getClass().getResourceAsStream(
                "RegexLinkExtractorTest.txt");

        var links = extractor.extractLinks(
                toCrawlDoc(docURL, ContentType.TEXT, is));
        is.close();

        for (String expectedURL : expectedURLs) {
            assertTrue(
                    contains(links, expectedURL),
                    "Could not find expected URL: "
                            + expectedURL);
        }

        Assertions.assertEquals(
                expectedURLs.length, links.size(),
                "Invalid number of links extracted.");
    }

    @Test
    void testJSLinkFromXML() throws IOException {
        var baseURL = "http://www.example.com/";
        var baseDir = baseURL + "test/";
        var docURL = baseDir + "RegexLinkExtractorTest.html";

        var extractor = new RegexLinkExtractor();
        try (Reader r = new InputStreamReader(
                getClass().getResourceAsStream(
                        getClass().getSimpleName()
                                + ".cfg.xml"))) {
            BeanMapper.DEFAULT.read(extractor, r, Format.XML);
        }
        // All these must be found
        String[] expectedURLs = {
                baseURL + "page?id=12345",
                baseURL + "page?id=67890",
        };

        Set<Link> links;
        try (var is = getClass().getResourceAsStream(
                "RegexLinkExtractorTest.txt")) {
            links = extractor.extractLinks(
                    toCrawlDoc(docURL, ContentType.TEXT,
                            is));
        }

        for (String expectedURL : expectedURLs) {
            assertTrue(
                    contains(links, expectedURL),
                    "Could not find expected URL: "
                            + expectedURL);
        }

        Assertions.assertEquals(
                expectedURLs.length, links.size(),
                "Invalid number of links extracted.");
    }

    @Test
    void testGenericWriteRead() {
        var extractor = new RegexLinkExtractor();
        extractor.getConfiguration()
                .setPatterns(
                        List.of(
                                new ExtractionPattern(
                                        "\\[(.*?)\\]",
                                        "$1"),
                                new ExtractionPattern(
                                        "<link>.*?</link>",
                                        "$1")))
                .setCharset(UTF_8)
                .setMaxUrlLength(12345);
        assertThatNoException().isThrownBy(
                () -> BeanMapper.DEFAULT
                        .assertWriteRead(extractor));
    }

    @Test
    void testFromFieldAndRestrictions() throws IOException {
        var extractor = new RegexLinkExtractor();
        var cfg = extractor.getConfiguration();
        cfg.setPatterns(
                List.of(new ExtractionPattern("http:.*?\\.html",
                        null)));
        cfg.getRestrictions().add(
                new PropertyMatcher(TextMatcher.regex(".*")));
        cfg.getFieldMatcher().setPattern("myfield");

        var doc = toCrawlDoc("n/a",
                ContentType.TEXT,
                NullInputStream.nullInputStream());
        doc.getMetadata().set("myfield",
                "http://one.com/1.html|http://two.com/2.html|NOT_ME");
        var links = extractor.extractLinks(doc);
        assertThat(links).map(Link::getUrl).containsExactlyInAnyOrder(
                "http://one.com/1.html",
                "http://two.com/2.html");

        cfg.clearPatterns();
        cfg.clearRestrictions();
        cfg.setContentTypeMatcher(TextMatcher.basic("application/pdf"));
        links = extractor.extractLinks(doc);
        assertThat(links).isEmpty();
    }

    @Test
    void testNoRestrictionMatch() throws IOException {
        var extractor = new RegexLinkExtractor();
        var cfg = extractor.getConfiguration();
        cfg.getRestrictions().add(
                new PropertyMatcher(TextMatcher.regex("NOPE")));

        var doc = toCrawlDoc("n/a",
                ContentType.TEXT,
                NullInputStream.nullInputStream());
        var links = extractor.extractLinks(doc);
        assertThat(links).isEmpty();
    }

    @Test
    void testLargeContent() throws IOException {
        var doc = toCrawlDoc("n/a", ContentType.TEXT,
                new ByteArrayInputStream(
                        ("http://one.com/1.html"
                                + "X".repeat(RegexLinkExtractor.MAX_BUFFER_SIZE)
                                + "http://two.com/2.html"
                                + "X".repeat(
                                        RegexLinkExtractor.MAX_BUFFER_SIZE))
                                                .getBytes()));
        var extractor = new RegexLinkExtractor();
        extractor.getConfiguration().setPatterns(
                List.of(new ExtractionPattern("http:.*?\\.html",
                        null)));
        var links = extractor.extractLinks(doc);
        assertThat(links).map(Link::getUrl).containsExactlyInAnyOrder(
                "http://one.com/1.html",
                "http://two.com/2.html");
    }

    private boolean contains(Set<Link> links, String url) {
        for (Link link : links) {
            if (url.equals(link.getUrl())) {
                return true;
            }
        }
        return false;
    }

    private Doc toCrawlDoc(String ref, ContentType ct, InputStream is) {
        return CrawlDocStubs.crawlDoc(ref, ct, is);
    }
}
