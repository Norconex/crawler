/* Copyright 2010-2026 Norconex Inc.
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
import static org.assertj.core.api.Assertions.assertThatException;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.apache.commons.beanutils.MethodUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BrokenInputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.commons.lang.file.ContentType;
import com.norconex.commons.lang.map.PropertyMatcher;
import com.norconex.commons.lang.map.PropertyMatchers;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.crawler.web.doc.operations.link.Link;
import com.norconex.crawler.web.doc.operations.link.LinkExtractor;
import com.norconex.crawler.web.stubs.CrawlDocStubs;
import com.norconex.importer.doc.Doc;

/**
 * Tests {@link LinkExtractor} implementations that are common
 * to HTML and DOM link extractors.
 */
@Timeout(30)
@SuppressWarnings("null")
class HtmlDomLinkExtractorTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("testExtractBetweenProvider")
    void testExtractBetween(LinkExtractor extractor) throws IOException {

        var baseURL = "http://www.example.com/";

        // All these must be found
        String[] expectedURLs = {
                baseURL + "include1.html",
                baseURL + "include2.html",
                baseURL + "include3.html",
                baseURL + "include4.html",
                baseURL + "include5.html",
        };

        Set<Link> links;
        try (var is = getClass().getResourceAsStream(
                "LinkExtractBetweenTest.html")) {
            links = extractor.extractLinks(
                    CrawlDocStubs.crawlDoc(
                            baseURL + "LinkExtractBetweenTest.html",
                            ContentType.HTML, is));
        }

        var actualUrls = links.stream().map(Link::getUrl).toList();
        assertThat(actualUrls).containsExactlyInAnyOrder(expectedURLs);
    }

    static Stream<LinkExtractor> testExtractBetweenProvider() {
        var htmlExtractor = new HtmlLinkExtractor();
        htmlExtractor.getConfiguration().addExtractSelectors(
                List.of("include1", "include2"));
        htmlExtractor.getConfiguration().addNoExtractSelectors(
                List.of("exclude1", "exclude2"));
        var domExtractor = new DomLinkExtractor();
        domExtractor.getConfiguration().addExtractSelectors(
                List.of("include1", "include2"));
        domExtractor.getConfiguration().addNoExtractSelectors(
                List.of("exclude1", "exclude2"));
        return Stream.of(
                htmlExtractor,
                domExtractor);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(textBlock = """
            html, fromField
            dom, fromField
            html, fromBody
            dom, fromBody
            """)
    void testExtractAttributes(String implType, String from)
            throws IOException {
        //    @MethodSource("testExtractAttributesProvider")
        //    void testExtractAttributes(LinkExtractor extractor) throws IOException {

        LinkExtractor extractor;
        if ("html".equals(implType)) {
            var htmlEx = new HtmlLinkExtractor();
            htmlEx.getConfiguration().addLinkTag("link", "href");
            if ("fromField".equals(from)) {
                htmlEx.getConfiguration()
                        .setFieldMatcher(TextMatcher
                                .basic("myfield"));
            }
            extractor = htmlEx;
        } else {
            var domEx = new DomLinkExtractor();
            if ("fromField".equals(from)) {
                domEx.getConfiguration()
                        .setFieldMatcher(TextMatcher
                                .basic("myfield"));
            }
            extractor = domEx;
        }

        var baseURL = "http://www.example.com/";
        var pageName = "LinkAttributesExtractorTest.html";
        var pageURL = baseURL + pageName;

        var link0 = new Link(baseURL + "0-meta.html");
        link0.setReferrer(pageURL);
        link0.getMetadata().add("tag", "meta");
        link0.getMetadata().add("attr", "content");
        link0.getMetadata().add("attr.http-equiv", "refresh");

        var link1 = new Link(baseURL + "1-a.html");
        link1.setReferrer(pageURL);
        link1.getMetadata().add("tag", "a");
        link1.getMetadata().add("attr", "href");
        link1.getMetadata().add("text", "A Link Text");
        link1.getMetadata().add("attr.class", "btn btn-primary");
        link1.getMetadata().add("attr.title", "A Title");
        link1.getMetadata().add("attr.data-type", "regular");

        var link2 = new Link(baseURL + "2-link.css");
        link2.setReferrer(pageURL);
        link2.getMetadata().add("tag", "link");
        link2.getMetadata().add("attr", "href");
        link2.getMetadata().add("attr.rel", "stylesheet");
        link2.getMetadata().add("attr.type", "text/css");

        var link3 = new Link(baseURL + "3-img.jpg");
        link3.setReferrer(pageURL);
        link3.getMetadata().add("tag", "img");
        link3.getMetadata().add("attr", "src");
        link3.getMetadata().add("attr.title", "Image Title");
        link3.getMetadata().add(
                "attr.style",
                "width: 64px; display: inline-block");
        link3.getMetadata().add("attr.alt", "Image Alt");

        Set<Link> expectedLinks = new TreeSet<>(
                List.of(link0, link1, link2, link3));

        Set<Link> links;
        try (var is = getClass().getResourceAsStream(
                "LinkAttributesExtractorTest.html")) {
            Doc doc;
            if ("fromBody".equals(from)) {
                doc = CrawlDocStubs.crawlDoc(pageURL,
                        ContentType.HTML, is);
            } else {
                doc = CrawlDocStubs.crawlDoc(
                        pageURL, ContentType.HTML,
                        InputStream.nullInputStream());
                doc.getMetadata().set("myfield",
                        IOUtils.toString(is, UTF_8));
            }

            links = extractor.extractLinks(doc);
        }
        assertThat(links).containsExactlyInAnyOrderElementsOf(
                expectedLinks);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("testExtractorProvider")
    void testRestrictions(LinkExtractor extractor)
            throws IOException, NoSuchMethodException,
            IllegalAccessException,
            InvocationTargetException {
        // with restrictions, it shall skip extractions and not even attempt
        // to read the input stream (thus, shall not fail)
        var doc = CrawlDocStubs.crawlDoc(
                "n/a",
                ContentType.HTML, BrokenInputStream.INSTANCE);

        var links = extractor.extractLinks(doc);
        assertThat(links).isEmpty();

        var config = ((Configurable<?>) extractor).getConfiguration();

        //TODO have a "Restrictable" interface or something like this to
        // avoid using reflection here.
        var restrictions = (PropertyMatchers) MethodUtils.invokeMethod(
                config, "getRestrictions", null);

        assertThat((List<?>) restrictions).hasSize(1);
        MethodUtils.invokeMethod(config, "clearRestrictions", null);
        assertThat((List<?>) restrictions).isEmpty();

        assertThatException()
                .isThrownBy(() -> extractor.extractLinks(doc))
                .withMessageContaining("Broken input stream");
    }

    static Stream<LinkExtractor> testExtractorProvider() {
        var htmlExtractor = new HtmlLinkExtractor();
        htmlExtractor
                .getConfiguration()
                .getRestrictions()
                .add(new PropertyMatcher(
                        TextMatcher.basic("NOMATCH")));

        var domExtractor = new DomLinkExtractor();
        domExtractor.getConfiguration()
                .getRestrictions()
                .add(new PropertyMatcher(
                        TextMatcher.basic("NOMATCH")));

        var tikaExtractor = new TikaLinkExtractor();
        tikaExtractor.getConfiguration()
                .getRestrictions()
                .add(new PropertyMatcher(
                        TextMatcher.basic("NOMATCH")));

        var feedExtractor = new XmlFeedLinkExtractor();
        feedExtractor
                .getConfiguration()
                .getRestrictions()
                .add(new PropertyMatcher(
                        TextMatcher.basic("NOMATCH")));
        feedExtractor
                .getConfiguration()
                .setContentTypeMatcher(TextMatcher.regex(".*"));

        return Stream.of(
                htmlExtractor,
                domExtractor,
                tikaExtractor,
                feedExtractor);
    }

    static Stream<LinkExtractor> testExtractAttributesProvider() {
        var htmlExtractor = new HtmlLinkExtractor();
        htmlExtractor.getConfiguration().addLinkTag("link", "href");
        var domExtractor = new DomLinkExtractor();
        return Stream.of(
                htmlExtractor,
                domExtractor);
    }
}
