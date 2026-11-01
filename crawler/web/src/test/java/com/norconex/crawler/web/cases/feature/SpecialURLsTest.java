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

import org.junit.jupiter.api.Timeout;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerSettings;

import com.norconex.committer.core.UpsertRequest;
import com.norconex.crawler.web.WebCrawlerConfig;
import com.norconex.crawler.web.junit.WebCrawlingTest;
import com.norconex.crawler.web.junit.WebCrawlingTestCapturer;

/**
 * Test that special characters in URLs are handled properly.
 */
@MockServerSettings
@Timeout(30)
@SuppressWarnings("null")
class SpecialURLsTest {

    @WebCrawlingTest
    void testSpecialURLs(ClientAndServer client, WebCrawlerConfig cfg) {

        var basePath = "/specialUrls";
        var baseUrl = serverUrl(client, basePath);
        var homePath = basePath + "/index.html";

        // @formatter:off
        client
            .when(request(homePath))
            .respond(response()
                .withBody(
                    """
                    <p>This page contains URLs with special characters that may
                    potentially cause issues if not handled properly.</p>
                    <a href="escaped%2Falready.html">Slashes Already Escaped</a>
                    <br><a href="co,ma.html?param=a,b&par,am=c,,d">Commas</a>
                    <br><a href="spa ce.html?param=a b&par am=c d">Spaces</a>
                    <br>
                    """,
                    HTML_UTF_8));

        client
            .when(request("!" + homePath))
            .respond(response().withBody("A page to be crawled.", HTML_UTF_8));
        // @formatter:on

        cfg.setStartReferences(List.of(serverUrl(client, homePath)));
        var mem = WebCrawlingTestCapturer.crawlAndCapture(cfg).getCommitter();

        assertThat(mem.getUpsertRequests())
                .map(UpsertRequest::getReference)
                .containsExactlyInAnyOrder(
                        baseUrl + "/index.html",
                        baseUrl + "/escaped%2Falready.html",
                        baseUrl + "/co,ma.html?param=a%2Cb&par%2Cam=c%2C%2Cd",
                        baseUrl + "/spa%20ce.html?param=a+b&par+am=c+d");
    }
}
