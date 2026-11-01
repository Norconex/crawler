/* Copyright 2010-2025 Norconex Inc.
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.commons.io.input.BrokenInputStream;
import org.apache.commons.io.input.NullInputStream;
import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.file.ContentType;
import com.norconex.crawler.web.WebTestUtil;
import com.norconex.crawler.web.doc.operations.link.Link;
import com.norconex.crawler.web.doc.operations.link.LinkExtractor;
import com.norconex.crawler.web.stubs.CrawlDocStubs;

/**
 * Tests {@link LinkExtractor} implementations that focuses on HTML or
 * HTML-like web pages.
 */
@SuppressWarnings("null")
class HtmlDomTikaLinkExtractorTest {

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ParameterizedTest(name = "{0}")
    @MethodSource("linkExtractorProvider")
    @interface LinkExtractorsTest {
    }

    static Stream<LinkExtractor> linkExtractorProvider() {
        var hle = new HtmlLinkExtractor();
        hle.getConfiguration().addLinkTag("link", null);
        return Stream.of(
                hle,
                new DomLinkExtractor(),
                new TikaLinkExtractor());
    }

    //--- Common tests ---------------------------------------------------------
    @LinkExtractorsTest
    void testLinkExtraction(LinkExtractor extractor)
            throws IOException {
        var baseURL = "http://www.example.com/";
        var baseDir = baseURL + "test/";
        var docURL = baseDir + "LinkExtractorTest.html";

        // All these must be found
        String[] expectedURLs = {
                baseURL + "meta-redirect.html",
                baseURL + "startWithDoubleslash.html",
                docURL + "?startWith=questionmark",
                docURL + "#startWithHashMark",
                baseURL + "startWithSlash.html",
                baseDir + "relativeToLastSegment.html",
                "http://www.sample.com/blah.html",
                baseURL + "onTwoLines.html",
                baseURL + "imageSlash.gif",
                baseURL + "imageNoSlash.gif",
                baseDir + "titleTarget.html",
                baseURL + "htmlEntities",
                baseURL + "?p1=v1&p2=v2&p3=v3",
                baseURL + "contains two spaces.html",
        };
        // All these must NOT be found
        String[] unexpectedURLs = {
                baseURL + "badhref.html",
                baseURL + "nofollow.html",
                baseURL + "/dont/process/scripts/'+variable+'",
                baseURL + "/dont/process/a/'+inscript+'",
                baseURL + "comment.html",
                baseDir, // empty href
        };

        // Only HtmlLinkExtractor:
        if (extractor instanceof HtmlLinkExtractor) {
            String[] additionalURLs = {
                    baseURL + "addedTagNoAttribUrlInBody.html",
                    baseURL + "addedTagAttribUrlInBody.html",
            };
            expectedURLs = ArrayUtils.addAll(expectedURLs, additionalURLs);

            String[] fewerURLs = {
                    "tel:123",
                    "mailto:blah@blah.com"
            };
            unexpectedURLs = ArrayUtils.addAll(unexpectedURLs, fewerURLs);
        }
        // Only TikaLinkExtractor:
        if (extractor instanceof TikaLinkExtractor) {
            String[] additionalURLs = {
                    "tel:123",
                    "mailto:blah@blah.com"
            };
            expectedURLs = ArrayUtils.addAll(expectedURLs, additionalURLs);
        }

        var is = getClass().getResourceAsStream(
                "LinkExtractorTest.html");

        var links = extractor.extractLinks(
                CrawlDocStubs.crawlDoc(docURL, ContentType.HTML, is));
        is.close();

        var actualUrls = links.stream().map(Link::getUrl).toList();
        assertThat(actualUrls).containsExactlyInAnyOrder(expectedURLs);
    }

    @LinkExtractorsTest
    void testNofollowRelVariantsAreIgnoredByDefault(LinkExtractor extractor)
            throws IOException {
        var docURL = "http://www.example.com/test/rel-variants.html";
        var content = """
                                <html><body>
                                <a href=\"/nofollow-spaces.html\" rel=\" nofollow \">A</a>
                                <a href=\"/nofollow-case.html\" rel=\"NoFoLlOw\">B</a>
                                <a href=\"/nofollow-multi.html\" rel=\"noopener nofollow ugc\">C</a>
                                <a href=\"/follow.html\" rel=\"noopener\">D</a>
                                </body></html>
                                """;

        var links = extractor.extractLinks(
                CrawlDocStubs.crawlDocHtml(docURL, content));

        var actualUrls = links.stream().map(Link::getUrl).toList();
        assertThat(actualUrls)
                .containsExactly("http://www.example.com/follow.html");
    }

    //--- BASE HREF Tests ------------------------------------------------------

    @LinkExtractorsTest
    void testBaseHrefLinkExtraction(
            LinkExtractor extractor) throws IOException {
        var docURL = "http://www.example.com/test/absolute/"
                + "LinkBaseHrefTest.html";
        var host = "http://www.sample.com";
        var baseURL = host + "/blah/";

        // All these must be found
        String[] expectedURLs = {
                baseURL + "a/b/c.html",
                host + "/d/e/f.html",
                "http://www.sample.com/g/h/i.html",
                "http://www.anotherhost.com/k/l/m.html",
        };

        var is = getClass().getResourceAsStream("LinkBaseHrefTest.html");
        var links = extractor.extractLinks(
                CrawlDocStubs.crawlDoc(docURL, ContentType.HTML, is));
        is.close();

        var actualUrls = links.stream().map(Link::getUrl).toList();
        assertThat(actualUrls).containsExactlyInAnyOrder(expectedURLs);
    }

    @LinkExtractorsTest
    void testRelativeBaseHrefLinkExtraction(
            LinkExtractor extractor) throws IOException {

        if (extractor instanceof TikaLinkExtractor) {
            // TikaLinkExtractor does not support all the same test cases
            // as the generic one. Consider updating it or deprecating it.
            return;
        }

        var docURL = "http://www.example.com/test/relative/"
                + "LinkRelativeBaseHrefTest.html";

        // All these must be found
        String[] expectedURLs = {
                "http://www.example.com/test/relative/blah.html?param=value",
                "http://www.example.com/d/e/f.html",
                "http://www.example.com/test/relative/path^blah.html",
                "http://www.anotherhost.com/k/l/m.html",
        };

        var is = getClass().getResourceAsStream(
                "LinkRelativeBaseHrefTest.html");
        var links = extractor.extractLinks(
                CrawlDocStubs.crawlDoc(docURL, ContentType.HTML, is));
        is.close();

        var actualUrls = links.stream().map(Link::getUrl).toList();
        assertThat(actualUrls).containsExactlyInAnyOrder(expectedURLs);
    }

    //--- Referrer Data Tests --------------------------------------------------

    @LinkExtractorsTest
    void testLinkKeepReferrer(LinkExtractor extractor) throws IOException {
        // All these must be found
        Set<Link> expectedLinks = new HashSet<>(
                Arrays.asList(
                        linkWithReferrer(
                                "1-notitle-notext.html", null, null, null),
                        linkWithReferrer(
                                "2-notitle-yestext.html",
                                "2 Yes Text",
                                null,
                                null),
                        linkWithReferrer(
                                "3-yestitle-yestext.html",
                                "3 Yes Text",
                                null,
                                "3 Yes Title"),
                        linkWithReferrer(
                                "4-yestitle-notext.html", null, null,
                                "4 Yes Title"),
                        // Link 5 should not be there (no href).
                        linkWithReferrer(
                                "6-yestitle-yestexthtml.html",
                                "[6]Yes Text",
                                (extractor instanceof TikaLinkExtractor)
                                        ? null
                                        : "[<font color=\"red\">6</font>]Yes Text",
                                "6 Yes Title")));

        var is = getClass().getResourceAsStream(
                "LinkKeepReferrerTest.html");
        var links = extractor.extractLinks(
                CrawlDocStubs.crawlDoc(
                        "http://www.site.com/parent.html",
                        ContentType.HTML, is));
        is.close();

        assertThat(links).containsExactlyInAnyOrderElementsOf(expectedLinks);
    }

    @LinkExtractorsTest
    void testWriteRead(LinkExtractor extractor) {
        LinkExtractor randomEx = WebTestUtil.randomize(extractor.getClass());
        assertThatNoException()
                .isThrownBy(() -> BeanMapper.DEFAULT.assertWriteRead(randomEx));
    }

    private Link linkWithReferrer(
            String relURL, String text, String markup, String title) {
        var link = new Link("http://www.site.com/" + relURL);
        link.setReferrer("http://www.site.com/parent.html");
        link.getMetadata().set("tag", "a");
        link.getMetadata().set("attr", "href");
        if (text != null) {
            link.getMetadata().set("text", text);
        }
        if (markup != null) {
            link.getMetadata().set("markup", markup);
        }
        if (title != null) {
            link.getMetadata().set("attr.title", title);
        }
        return link;
    }

    @LinkExtractorsTest
    void testNonMatchingContentType(LinkExtractor extractor)
            throws IOException {
        var links = extractor.extractLinks(
                CrawlDocStubs.crawlDoc(
                        "http://www.site.com/file.pdf",
                        ContentType.PDF, NullInputStream.nullInputStream()));
        assertThat(links).isEmpty();
    }

    @LinkExtractorsTest
    void testFailingDocStream(LinkExtractor extractor) {
        assertThatException().isThrownBy(() -> {//NOSONAR
            extractor.extractLinks(
                    CrawlDocStubs.crawlDoc(
                            "http://www.site.com/file.html",
                            ContentType.HTML,
                            BrokenInputStream.INSTANCE));
        });
    }
}
