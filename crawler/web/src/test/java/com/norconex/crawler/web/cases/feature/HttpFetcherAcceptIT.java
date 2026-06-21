/* Copyright 2021-2026 Norconex Inc.
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
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerSettings;
import org.mockserver.model.MediaType;

import com.hazelcast.core.Hazelcast;
import com.norconex.commons.lang.config.Configurable;
import com.norconex.crawler.core.fetch.FetchDirectiveSupport;
import com.norconex.crawler.web.fetch.HttpMethod;
import com.norconex.crawler.web.fetch.impl.httpclient.HttpClientFetcher;
import com.norconex.crawler.web.junit.WebCrawlingTestCapturer;
import com.norconex.crawler.web.stubs.CrawlerConfigStubs;

/**
 * Tests that a page is only fetched by the fetcher we are interested in.
 */
//Related to https://github.com/Norconex/collector-http/issues/654
@MockServerSettings
@Timeout(30)
class HttpFetcherAcceptIT {
    /**
     * Waits until the MockServer responds with 200 OK for the given path, or times out after 2 seconds.
     */
    private void waitForMockServerReady(ClientAndServer client, String path) {
        long deadline = System.currentTimeMillis() + 2000;
        boolean ready = false;
        Exception last = null;
        while (System.currentTimeMillis() < deadline && !ready) {
            try {
                var response = client
                        .retrieveActiveExpectations(request().withPath(path));
                if (response != null && response.length > 0) {
                    ready = true;
                    break;
                }
            } catch (Exception e) {
                last = e;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
        }
        if (!ready && last != null) {
            throw new RuntimeException("MockServer not ready for path: " + path,
                    last);
        }
    }

    @AfterEach
    void tearDown() {
        // Ensure all Hazelcast instances are shut down after each invocation
        // to prevent resource accumulation and port conflicts
        Hazelcast.shutdownAll();
        try {
            Thread.sleep(500); // Give Hazelcast time to fully shut down
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final String HOME_PATH = "/fetchAccept";

    @TempDir
    private Path tempDir;

    /*
     * Tests:
     * - Run 1: HEAD: disabled; GET: required-success (default config).
     *   Expected: Response from GET only.
     * - Run 2: HEAD: required-success; GET: disabled.
     *   Expected: Response from HEAD only.
     * - Run 3: HEAD: required-success; GET: required-success.
     *   Expected: Response from both GET and HEAD meta, but since we
     *   only set headers once (onSet OPTIONAL), we get only 1 HEAD meta,
     *   with GET content
     * - Run 4: HEAD: required-fail; GET: optional-success.
     *   Expected: No response
     * - Run 5: HEAD: required-success; GET: required-fail.
     *   Expected: No response.
     * - Run 6: HEAD: optional-fail, GET: optional-success.
     *   Expected: Response from GET.
     * - Run 7: HEAD: optional-success, GET: optional-fail.
     *   Expected: Response from HEAD.
     */

    @ParameterizedTest
    @CsvSource(
        nullValues = "null",
        delimiter = '|',
        textBlock =
        //  --------- Crawler config -----------+--- Expectations --
        //  HEAD     | Meta  | GET      | Doc   | Doc | From | From
        //  Support  | Filt. | Support  | Filt. | Cnt | Meta | Doc
        //  ---------+-------+----------+-------+-----+------+------
        """
            DISABLED | false | REQUIRED | false | 1   | GET  | GET
            REQUIRED | false | DISABLED | false | 1   | HEAD | null
            REQUIRED | false | REQUIRED | false | 1   | HEAD | GET
            REQUIRED | true  | OPTIONAL | false | 0   | null | null
            REQUIRED | false | REQUIRED | true  | 0   | null | null
            OPTIONAL | true  | OPTIONAL | false | 1   | GET  | GET
            OPTIONAL | false | OPTIONAL | true  | 1   | HEAD | null
            """
    )
    void testHttpFetcherAccept(
            FetchDirectiveSupport headSupport, boolean metaRejectedByFilter,
            FetchDirectiveSupport getSupport, boolean docRejectedByFilter,
            int expectedUpsertCount,
            String expectedMetaValue,
            String expectedDocValue,
            ClientAndServer client) throws IOException {

        whenHttpMethod(client, HttpMethod.HEAD);
        whenHttpMethod(client, HttpMethod.GET);

        // Wait for MockServer to register expectations before crawling
        waitForMockServerReady(client, HOME_PATH);

        var cfg = CrawlerConfigStubs.memoryCrawlerConfig(tempDir);
        cfg.setStartReferences(List.of(serverUrl(client, HOME_PATH)));
        // Configure 2 fetches, one doing HEAD, the other doing GET

        cfg.setMetadataFetchSupport(headSupport);
        var headFetcher = createFetcher(HttpMethod.HEAD);
        if (metaRejectedByFilter) {
            headFetcher.getConfiguration()
                    .setReferenceFilters(List.of(ref -> false));
        }

        cfg.setDocumentFetchSupport(getSupport);
        var getFetcher = createFetcher(HttpMethod.GET);
        if (docRejectedByFilter) {
            getFetcher.getConfiguration()
                    .setReferenceFilters(List.of(ref -> false));
        }

        cfg.setFetchers(List.of(headFetcher, getFetcher));

        var mem = WebCrawlingTestCapturer.crawlAndCapture(cfg).getCommitter();

        assertThat(mem.getUpsertCount()).isEqualTo(expectedUpsertCount);
        if (expectedUpsertCount > 0) {
            var req = mem.getUpsertRequests().get(0);

            var actualMetaValue = req.getMetadata().getStrings("whatAmI");
            var actualDocValue = IOUtils.toString(req.getContent(), UTF_8);

            if (expectedMetaValue != null) {
                assertThat(actualMetaValue)
                        .containsExactly(expectedMetaValue.split(","));
            } else {
                assertThat(actualMetaValue).isNull();
            }

            if (expectedDocValue != null) {
                assertThat(actualDocValue)
                        .isEqualTo("I am " + expectedDocValue);
            } else {
                assertThat(actualDocValue).isBlank();
            }
        }
    }

    private void whenHttpMethod(ClientAndServer client, HttpMethod method) {
        client
                .when(request()
                        .withMethod(method.name())
                        .withPath(HOME_PATH))
                .respond(response()
                        .withHeader("whatAmI", method.name())
                        .withBody(
                                "I am " + method.name(),
                                MediaType.HTML_UTF_8));
    }

    private HttpClientFetcher createFetcher(HttpMethod method) {
        return Configurable.configure(
                new HttpClientFetcher(),
                cfg -> cfg.setHttpMethods(List.of(method)));
    }
}
