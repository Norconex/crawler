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

import static com.norconex.crawler.web.mocks.MockWebsite.htmlPage;
import static com.norconex.crawler.web.mocks.MockWebsite.serverUrl;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerSettings;
import org.mockserver.model.MediaType;

import com.norconex.crawler.web.WebCrawlerConfig;
import com.norconex.crawler.web.fetch.impl.httpclient.HttpClientFetcher;
import com.norconex.crawler.web.junit.WebCrawlingTest;
import com.norconex.crawler.web.junit.WebCrawlingTestCapturer;
import com.norconex.importer.doc.DocMetaConstants;
import org.junit.jupiter.api.Timeout;

/**
 * Test proper charset detection when the declared one does not match
 * actual one.
 */
// Related ticket: https://github.com/Norconex/importer/issues/41
@MockServerSettings
@Timeout(60) // several crawls: hang guard with room for loaded CI
class ContentTypeCharsetTest {

    @WebCrawlingTest
    void testContentTypeCharset(
            ClientAndServer client, WebCrawlerConfig cfg) {

        var urlPath = "/contentTypeCharset";

        //--- Web site ---

        // @formatter:off
        client
            .when(request()
                .withPath(urlPath))
            .respond(response()
                .withContentType(new MediaType(
                        "application","javascript").withCharset("Big5"))
                .withBody(htmlPage()
                    .title("ContentType + Charset ☺☻")
                    .body("""
                            This page returns the Content-Type as
                            "application/javascript; charset=Big5"
                            while in reality it is
                            "text/html; charset=UTF-8".
                            Éléphant à noël. ☺☻
                            """)
                    .build()
                    .getBytes(UTF_8)));
        // @formatter:on

        //--- First run without detect ---

        cfg.setWorkDir(cfg.getWorkDir().resolve("1"));
        cfg.setStartReferences(List.of(serverUrl(client, urlPath)));
        var mem1 = WebCrawlingTestCapturer.crawlAndCapture(cfg).getCommitter();

        assertThat(mem1.getUpsertRequests()).hasSize(1);
        var doc1 = mem1.getUpsertRequests().get(0);
        assertThat(doc1.getMetadata().getString(DocMetaConstants.CONTENT_TYPE))
                .isEqualTo("application/javascript");
        assertThat(
                doc1.getMetadata().getString(DocMetaConstants.CONTENT_ENCODING))
                        .isEqualTo("Big5");

        //--- Second run with detect ---

        cfg.setWorkDir(cfg.getWorkDir().resolve("2"));
        cfg.setStartReferences(List.of(serverUrl(client, urlPath)));
        var fetcher = new HttpClientFetcher();
        fetcher.getConfiguration().setForceContentTypeDetection(true);
        fetcher.getConfiguration().setForceCharsetDetection(true);
        cfg.setFetchers(List.of(fetcher));
        var mem2 = WebCrawlingTestCapturer.crawlAndCapture(cfg).getCommitter();

        assertThat(mem2.getUpsertRequests()).hasSize(1);
        var doc2 = mem2.getUpsertRequests().get(0);
        assertThat(doc2.getMetadata().getString(DocMetaConstants.CONTENT_TYPE))
                .isEqualTo("text/html");
        assertThat(
                doc2.getMetadata().getString(DocMetaConstants.CONTENT_ENCODING))
                        .isEqualTo(StandardCharsets.UTF_8.toString());
    }
}
