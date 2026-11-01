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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpStatusCode.INTERNAL_SERVER_ERROR_500;

import java.util.List;

import org.junit.jupiter.api.Timeout;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerSettings;
import org.mockserver.mock.action.ExpectationResponseCallback;
import org.mockserver.model.HttpClassCallback;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpStatusCode;

import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.event.listeners.DeleteRejectedEventListener;
import com.norconex.crawler.web.WebCrawlerConfig;
import com.norconex.crawler.web.junit.WebCrawlingTest;
import com.norconex.crawler.web.junit.WebCrawlingTestCapturer;

/**
 * Test the deletion of rejected references with
 * {@link DeleteRejectedEventListener}.
 */
@MockServerSettings
@Timeout(120)
@SuppressWarnings("null")
class RejectedRefsDeletionTest {
    private static final String PATH = "/rejectedRefsDeletion";

    @WebCrawlingTest
    void testRejectedRefsDeletionTest(
            ClientAndServer client, WebCrawlerConfig cfg) {
        client
                .when(request())
                .respond(HttpClassCallback.callback(Callback.class));

        var startRef = serverUrl(client, PATH);

        cfg.setStartReferences(List.of(startRef));
        var drel = new DeleteRejectedEventListener();
        drel.getConfiguration().setEventMatcher(
                TextMatcher.csv(
                        CrawlerEvent.REJECTED_NOTFOUND
                                + ", " + CrawlerEvent.REJECTED_BAD_STATUS));
        cfg.addEventListeners(List.of(drel));
        cfg.getImporterConfig().setHandlers(List.of(docCtx -> {
            if (docCtx.reference().endsWith("page=6-REJECTED_IMPORT")) {
                docCtx.rejectedBy("Rejected by ME");
            }
            return true;
        }));
        var mem = WebCrawlingTestCapturer.crawlAndCapture(cfg).getCommitter();

        // Expected:
        //   3 upserts, including main page,
        //   4 deletes (3 not found and 1 bad status)
        var upserts = mem.getUpsertRequests();
        var deletes = mem.getDeleteRequests();

        assertThat(upserts)
                .map(UpsertRequest::getReference)
                .containsExactlyInAnyOrder(
                        startRef,
                        startRef + "?page=1-OK",
                        startRef + "?page=3-OK");
        assertThat(deletes)
                .map(DeleteRequest::getReference)
                .containsExactlyInAnyOrder(
                        startRef + "?page=2-REJECTED_NOTFOUND",
                        startRef + "?page=4-REJECTED_BAD_STATUS",
                        startRef + "?page=5-REJECTED_NOTFOUND",
                        startRef + "?page=7-REJECTED_NOTFOUND");
    }

    public static class Callback implements ExpectationResponseCallback {
        @Override
        public HttpResponse handle(HttpRequest req) {
            var page = req.getFirstQueryStringParameter("page");

            if ("1-OK".equals(page)) {
                return response().withBody("Page 1 expected event: OK");
            }
            if ("2-REJECTED_NOTFOUND".equals(page)) {
                return response()
                        .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                        .withBody("Page 2 expected event: REJECTED_NOTFOUND");
            }
            if ("3-OK".equals(page)) {
                return response().withBody("Page 3 expected event: OK");
            }
            if ("4-REJECTED_BAD_STATUS".equals(page)) {
                return response()
                        .withStatusCode(INTERNAL_SERVER_ERROR_500.code())
                        .withBody("Page 4 expected event: REJECTED_BAD_STATUS");
            }
            if ("5-REJECTED_NOTFOUND".equals(page)) {
                return response()
                        .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                        .withBody("Page 5 expected event: REJECTED_NOTFOUND");
            }
            if ("6-REJECTED_IMPORT".equals(page)) {
                return response().withBody(
                        "Page 6 expected event: REJECTED_IMPORT");
            }
            if ("7-REJECTED_NOTFOUND".equals(page)) {
                return response()
                        .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                        .withBody("Page 7 expected event: REJECTED_NOTFOUND");
            }
            return response().withBody("""
                    <h1>DeleteRejectedEventListener test main page</h1>
                    <p>The test pages and expected events generated:</p>
                    <ol>
                    <li><a href="?page=1-OK">OK</a></li>
                    <li><a href="?page=2-REJECTED_NOTFOUND">delete #1</a></li>
                    <li><a href="?page=3-OK">OK</a></li>
                    <li><a href="?page=4-REJECTED_BAD_STATUS">delete #2</a></li>
                    <li><a href="?page=5-REJECTED_NOTFOUND">delete #3</a></li>
                    <li><a href="?page=6-REJECTED_IMPORT">
                        no delete, event does not match</a></li>
                    <li><a href="?page=2-REJECTED_NOTFOUND">
                        no delete, page 2 duplicate)</a></li>
                    <li><a href="?page=7-REJECTED_NOTFOUND">delete #4</a></li>
                    </ol>
                    """);
        }
    }
}
