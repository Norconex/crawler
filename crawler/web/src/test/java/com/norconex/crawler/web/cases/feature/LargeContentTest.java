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
import static com.norconex.crawler.web.mocks.MockWebsite.whenHtml;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerSettings;

import com.norconex.crawler.web.WebCrawlerConfig;
import com.norconex.crawler.web.fetch.impl.httpclient.HttpClientFetcher;
import com.norconex.crawler.web.junit.WebCrawlingTest;
import com.norconex.crawler.web.junit.WebCrawlingTestCapturer;

/**
 * Test that large files are processed properly (&gt; 2MB). It holds several
 * copies of a multi-MB page at once, and is the slowest of the crawl tests,
 * so it runs alone when test classes run in parallel.
 */
@Isolated
@MockServerSettings
@Timeout(30)
class LargeContentTest {

    @WebCrawlingTest
    void testLargeContent(ClientAndServer client, WebCrawlerConfig cfg)
            throws IOException {

        waitForServerRunning(client, Duration.ofSeconds(10));

        var minSize = 3 * 1024 * 1024;
        var path = "/largeContent";

        whenHtml(client, path,
                RandomStringUtils.secure().nextAlphanumeric(minSize));

        cfg.setStartReferences(List.of(serverUrl(client, path)));
        var fetcherCfg = ((HttpClientFetcher) cfg.getFetchers().get(0))
                .getConfiguration();
        fetcherCfg.setSocketTimeout(Duration.ofSeconds(60))
                .setConnectionTimeout(Duration.ofSeconds(60))
                .setConnectionRequestTimeout(
                        Duration.ofSeconds(60));

        var mem = WebCrawlingTestCapturer.crawlAndCapture(cfg)
                .getCommitter();

        var txt = IOUtils.toString(
                mem.getUpsertRequests().get(0).getContent(),
                UTF_8);

        assertThat(txt).hasSizeGreaterThanOrEqualTo(minSize);
    }

    private static void waitForServerRunning(
            ClientAndServer client, Duration timeout) {

        long deadlineNanos = System.nanoTime() + timeout.toNanos();

        while (System.nanoTime() < deadlineNanos) {
            if (client.isRunning()) {
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting for MockServer to start.",
                        e);
            }
        }

        throw new IllegalStateException(
                "MockServer did not start within " + timeout + ".");
    }
}
