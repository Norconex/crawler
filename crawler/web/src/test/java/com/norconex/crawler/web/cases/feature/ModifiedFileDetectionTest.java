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
import static org.apache.http.HttpHeaders.LAST_MODIFIED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.MediaType.HTML_UTF_8;

import java.time.ZonedDateTime;
import java.util.List;

import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerSettings;

import com.norconex.committer.core.CommitterException;
import com.norconex.crawler.web.WebCrawlerConfig;
import com.norconex.crawler.web.WebTestUtil;
import com.norconex.crawler.web.junit.WebCrawlingTest;
import com.norconex.crawler.web.junit.WebCrawlingTestCapturer;
import org.junit.jupiter.api.Timeout;

/**
 * Test detection of modified files.
 */
@MockServerSettings
@Timeout(60) // several crawls: hang guard with room for loaded CI
class ModifiedFileDetectionTest {

    private static final ZonedDateTime twentyDaysAgo =
            ZonedDateTime.now().minusDays(20).withNano(0);

    private final String homePath = "/modifiedFileDetection";
    private final String dynaDatePath = homePath + "/dynaHeaderDate";
    private final String dynaContentPath = homePath + "/dynaContent";
    private final String dynaDateContentPath = homePath + "/dynaDateContent";

    @WebCrawlingTest
    void testModifiedFileDetection(
            ClientAndServer client, WebCrawlerConfig cfg)
            throws CommitterException {

        // relies on default checksummers
        cfg.setStartReferences(List.of(serverUrl(client, homePath)));

        // First run is all new, so 4 docs
        whenLastModified(client, 0);
        var mem = WebCrawlingTestCapturer.crawlAndCapture(cfg).getCommitter();
        assertThat(mem.getUpsertCount()).isEqualTo(4);
        mem.clean();

        // Second run dates have changed on 3 pages but we expect only 1 to
        // be flagged as "modified" because the header checksum is only
        // use to tell is if we should also parse/check the content for change
        // (i.e., we do not take the header checksum word on it).
        // So on second run:
        //   - Page 1 (home) has same header date so we do not bother
        //     checking content and considers it unmodified.
        //   - Page 2 (first link) has a new modified header date so we check
        //     content, but content has not changed, so it is unmodified.
        //   - Page 3 (second link) has same header date so we do not bother
        //     checking content even if it has changed, so we considers it
        //     unmodified.
        //   - Page 4 (third link) has a new modified header date so we check
        //     content, and since content has also changed, we flag it
        //     as modified.
        whenLastModified(client, 1);
        mem = WebCrawlingTestCapturer.crawlAndCapture(cfg).getCommitter();
        assertThat(mem.getUpsertCount()).isEqualTo(1);
        mem.clean();
    }

    private void whenLastModified(
            ClientAndServer client, int dynaDaysOffset) {
        // @formatter:off
        client.reset();
        client
            .when(request().withPath(homePath))
            .respond(response()
                .withHeader(
                        LAST_MODIFIED,
                        WebTestUtil.rfcFormat(twentyDaysAgo))
                .withBody(
                        """
                        <h1>Home page.</h1>
                        <p>This page is never modified but the pages for these
                        3 links are:</p>
                        <a href="%s">Ever changing Last-Modified in HTTP
                        header</a>
                        <a href="%s">Ever changing body content</a>
                        <a href="%s">Both header and body are ever changing</a>
                        """
                        .formatted(
                                dynaDatePath,
                                dynaContentPath,
                                dynaDateContentPath),
                        HTML_UTF_8));
        client
            .when(request().withPath(dynaDatePath))
            .respond(response()
                .withHeader(
                        LAST_MODIFIED,
                        WebTestUtil.daysAgoRFC(15 - dynaDaysOffset))
                .withBody(
                        """
                        This page content is the same, but header should
                        be different each time (according to offset).
                        """,
                        HTML_UTF_8));
        client
            .when(request().withPath(dynaContentPath))
            .respond(response()
                .withHeader(
                        LAST_MODIFIED,
                        WebTestUtil.rfcFormat(twentyDaysAgo))
                .withBody(
                        """
                        This page content should be different (according to
                        offset) while the header should be the same.
                        Salt: %s.
                        """
                        .formatted(
                                WebTestUtil.daysAgoRFC(
                                        10 - dynaDaysOffset)),
                        HTML_UTF_8));
        client
            .when(request().withPath(dynaDateContentPath))
            .respond(
                response()
                    .withHeader(
                            LAST_MODIFIED,
                            WebTestUtil
                                    .daysAgoRFC(5 - dynaDaysOffset))
                    .withBody(
                        """
                        Both content and header should be different
                        (according to offset).
                        Salt: %s.
                        """
                        .formatted(
                                WebTestUtil.daysAgoRFC(
                                        5 - dynaDaysOffset)),
                        HTML_UTF_8));
        // @formatter:on
    }
}
