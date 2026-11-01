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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.file.ContentType;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.crawler.web.doc.operations.link.Link;
import com.norconex.crawler.web.doc.operations.link.LinkExtractor;
import com.norconex.crawler.web.stubs.CrawlDocStubs;

/**
 * Tests unique to {@link HtmlLinkExtractor}.
 */
@Timeout(30)
@SuppressWarnings("null")
class HtmlExtractorTest {

    @Test
    void testHtmlEquivRefreshIssue210() throws IOException {
        var html = """
                <html><head><meta http-equiv="refresh" \
                content="0; URL=en/91/index.html">\
                </head><body></body></html>""";
        var docURL = "http://db-artmag.com/index_en.html";
        LinkExtractor extractor = new HtmlLinkExtractor();
        var links = extractor.extractLinks(
                CrawlDocStubs.crawlDoc(
                        docURL, ContentType.HTML,
                        new ByteArrayInputStream(html.getBytes())));

        Assertions.assertEquals(
                1, links.size(),
                "Invalid number of links extracted.");
        Assertions.assertEquals(
                "http://db-artmag.com/en/91/index.html",
                links.iterator().next().getUrl());
    }

    @Test
    void testExtractionFromField() throws IOException {
        var html = """
                </head><body><a href="link.html">link</a></body></html>""";
        var docURL = "http://somewhere.com/index_en.html";
        var extractor = new HtmlLinkExtractor();
        extractor.getConfiguration().setFieldMatcher(
                TextMatcher.basic("patate"));

        var doc = CrawlDocStubs.crawlDoc(
                docURL, ContentType.HTML,
                InputStream.nullInputStream());
        doc.getMetadata().add("patate", html);
        var links = extractor.extractLinks(doc);

        Assertions.assertEquals(
                1, links.size(),
                "Invalid number of links extracted.");
        Assertions.assertEquals(
                "http://somewhere.com/link.html",
                links.iterator().next().getUrl());
    }

    @Test
    void testIssue188() throws IOException {
        var ref = "http://www.site.com/en/articles/articles.html"
                + "?param1=value1&param2=value2";
        var url = "http://www.site.com/en/articles/detail/article-x.html";
        var html = """
                <html><body>\
                <a href="/en/articles/detail/article-x.html">test link</a>\
                </body></html>""";
        var input = new ByteArrayInputStream(html.getBytes());
        var extractor = new HtmlLinkExtractor();
        var links = extractor.extractLinks(
                CrawlDocStubs.crawlDoc(ref, ContentType.HTML, input));
        input.close();
        assertThat(urlList(links)).containsExactly(url);
    }

    @Test
    void testIssue236() throws IOException {
        var url = "javascript:__doPostBack('MoreInfoList1$Pager','2')";
        var html = "<html><body>"
                + "<a href=\"" + url + "\">JavaScript link</a>"
                + "</body></html>";
        var input = new ByteArrayInputStream(html.getBytes());
        var extractor = new HtmlLinkExtractor();
        extractor.getConfiguration().setSchemes(List.of("javascript"));
        var links = extractor.extractLinks(
                CrawlDocStubs.crawlDoc("N/A", ContentType.HTML, input));
        input.close();
        assertThat(urlList(links)).containsExactly(url);
    }

    // Related to https://github.com/Norconex/collector-http/pull/312
    @Test
    void testHtmlBadlyFormedURL() throws IOException {
        var ref = "http://www.example.com/index.html";
        var url = "http://www.example.com/invalid^path^.html";
        var html = """
                <html><body>\
                <a href="/invalid^path^.html">test link</a>\
                </body></html>""";
        var input = new ByteArrayInputStream(html.getBytes());
        var extractor = new HtmlLinkExtractor();
        var links = extractor.extractLinks(
                CrawlDocStubs.crawlDoc(ref, ContentType.HTML, input));
        input.close();
        assertThat(urlList(links)).containsExactly(url);
    }

    //Test for: https://github.com/Norconex/collector-http/issues/423
    @Test
    void testUnquottedURL() throws IOException {
        var ref = "http://www.example.com/index.html";
        var url1 = "http://www.example.com/unquoted_url1.html";
        var url2 = "http://www.example.com/unquoted_url2.html";
        var html = """
                <html><body>\
                <a href=unquoted_url1.html>test link 1</a>\
                <a href=unquoted_url2.html title="blah">test link 2</a>\
                </body></html>""";
        var input = new ByteArrayInputStream(html.getBytes());
        var extractor = new HtmlLinkExtractor();
        var links = extractor.extractLinks(
                CrawlDocStubs.crawlDoc(ref, ContentType.HTML, input));
        input.close();
        assertThat(urlList(links)).containsExactlyInAnyOrder(url1, url2);
    }

    @Test
    void testBadQuotingURL() throws IOException {
        var ref = "http://www.example.com/index.html";
        var url1 = "http://www.example.com/bad\"quote1.html";
        var url2 = "http://www.example.com/bad'quote2.html";
        var url3 = "http://www.example.com/bad\"quote3.html";
        var html = """
                <html><body>\
                <a href=bad"quote1.html>test link 1</a>\
                <a href="bad'quote2.html">test link 1</a>\
                <a href='bad"quote3.html'>test link 1</a>\
                </body></html>""";
        var input = new ByteArrayInputStream(html.getBytes());
        var extractor = new HtmlLinkExtractor();
        var links = extractor.extractLinks(
                CrawlDocStubs.crawlDoc(ref, ContentType.HTML, input));
        input.close();
        assertThat(urlList(links)).containsExactlyInAnyOrder(url1, url2, url3);
    }

    @Test
    void testHtmlWriteRead() {
        var htmlExtractor = new HtmlLinkExtractor();
        htmlExtractor.getConfiguration()
                .setIgnoreNofollow(true)
                .addLinkTag("food", "chocolate")
                .addLinkTag("food", "candy")
                .addLinkTag("friend", "Thor")
                .addExtractBetween("start1", "end1", true)
                .addExtractBetween("start2", "end2", false)
                .addNoExtractBetween("nostart1", "noend1", true)
                .addNoExtractBetween("nostart2", "noend2", false);
        assertThatNoException().isThrownBy(
                () -> BeanMapper.DEFAULT.assertWriteRead(htmlExtractor));
    }

    private static List<String> urlList(Collection<Link> links) {
        return links.stream().map(Link::getUrl).toList();
    }
}
