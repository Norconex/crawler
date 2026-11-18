/* Copyright 2026 Norconex Inc.
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

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Timeout;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerSettings;
import org.mockserver.model.MediaType;

import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.impl.MemoryCommitter;
import com.norconex.crawler.web.WebCrawlerConfig;
import com.norconex.crawler.web.WebTestUtil;
import com.norconex.crawler.web.junit.WebCrawlingTest;
import com.norconex.crawler.web.junit.WebCrawlingTestCapturer;
import com.norconex.crawler.web.mocks.MockWebsite;
import com.norconex.importer.handler.splitter.impl.TextChunkSplitter;

/**
 * A page split into chunks that replace it, crawled again and again. The
 * chunks cannot be fetched by their own reference, so what becomes of them
 * follows their page: when it is unchanged (and so skipped) they stay.
 */
@MockServerSettings
@Timeout(120)
class WebSplitChildrenTest {

    private static final String PATH = "/split-children";

    private static String text(int sentences) {
        return IntStream.rangeClosed(1, sentences)
                .mapToObj(i -> "Sentence number " + i
                        + " says something that no other one says.")
                .collect(Collectors.joining(" "));
    }

    private static MemoryCommitter crawl(WebCrawlerConfig cfg)
            throws CommitterException {
        return WebCrawlingTestCapturer.crawlAndCapture(cfg).getCommitter();
    }

    private static List<String> deleted(MemoryCommitter mem) {
        return mem.getDeleteRequests().stream()
                .map(DeleteRequest::getReference)
                .filter(ref -> ref.contains("#chunk"))
                .sorted().toList();
    }

    @WebCrawlingTest
    void chunksOfAnUnchangedPageStayThroughCrawlAfterCrawl(
            ClientAndServer client, WebCrawlerConfig cfg)
            throws CommitterException {

        // The page never changes: same body, same date.
        var lastModified = WebTestUtil.daysAgoRFC(10);
        client.reset();
        client.when(request(PATH)).respond(response()
                .withHeader("Last-Modified", lastModified)
                .withBody(MockWebsite.htmlPage().body(text(40)).build(),
                        MediaType.HTML_UTF_8));

        var splitter = new TextChunkSplitter();
        splitter.getConfiguration().setMaxChunkSize(300).setChunkOverlap(0);
        cfg.setStartReferences(List.of(serverUrl(client, PATH)));
        cfg.getImporterConfig().setHandlers(List.of(splitter));

        var first = crawl(cfg);
        var chunks = first.getUpsertRequests().stream()
                .map(r -> r.getReference())
                .filter(ref -> ref.contains("#chunk")).toList();
        assertThat(chunks).hasSizeGreaterThan(2);
        first.clean();

        // Nothing changed: the page is skipped, its chunks are not seen, and
        // they cannot be fetched by their own reference. They must stay.
        for (var run = 2; run <= 4; run++) {
            var again = crawl(cfg);
            assertThat(deleted(again)).as("deletions of crawl " + run)
                    .isEmpty();
            assertThat(again.getUpsertCount()).as("upserts of crawl " + run)
                    .isZero();
            again.clean();
        }
    }

    private static void serve(ClientAndServer client, String lastModified,
            String body) {
        client.reset();
        client.when(request(PATH)).respond(response()
                .withHeader("Last-Modified", lastModified)
                .withBody(MockWebsite.htmlPage().body(body).build(),
                        MediaType.HTML_UTF_8));
    }

    private static WebCrawlerConfig splitting(
            WebCrawlerConfig cfg, ClientAndServer client) {
        var splitter = new TextChunkSplitter();
        splitter.getConfiguration().setMaxChunkSize(300).setChunkOverlap(0);
        cfg.setStartReferences(List.of(serverUrl(client, PATH)));
        cfg.getImporterConfig().setHandlers(List.of(splitter));
        return cfg;
    }

    private static List<String> chunksSent(MemoryCommitter mem) {
        return mem.getUpsertRequests().stream()
                .map(r -> r.getReference())
                .filter(ref -> ref.contains("#chunk")).sorted().toList();
    }

    @WebCrawlingTest
    void chunksAreStillKnownAfterTheirPageWasSkipped_soTheyAreReplacedWhenItChanges(
            ClientAndServer client, WebCrawlerConfig cfg)
            throws CommitterException {

        serve(client, WebTestUtil.daysAgoRFC(10), text(40));
        splitting(cfg, client);
        var first = chunksSent(crawl(cfg));
        assertThat(first).hasSizeGreaterThan(2);

        // The page is skipped as unmodified, twice.
        assertThat(deleted(crawl(cfg))).isEmpty();
        assertThat(deleted(crawl(cfg))).isEmpty();

        // Then it changes, and is much shorter: the chunks past its new end
        // are stale, and only possible to delete if they were still known.
        serve(client, WebTestUtil.daysAgoRFC(1), text(10));
        var changed = crawl(cfg);

        var stale = deleted(changed);
        assertThat(stale).isNotEmpty();
        var kept = first.size() - stale.size();
        assertThat(kept).isPositive();
        assertThat(stale).containsExactlyElementsOf(
                IntStream.rangeClosed(kept + 1, first.size())
                        .mapToObj(i -> serverUrl(client, PATH) + "#chunk" + i)
                        .sorted().toList());
    }

    @WebCrawlingTest
    void whenThePageGoesItsChunksGoWithIt(
            ClientAndServer client, WebCrawlerConfig cfg)
            throws CommitterException {

        serve(client, WebTestUtil.daysAgoRFC(10), text(40));
        splitting(cfg, client);
        var first = chunksSent(crawl(cfg));
        crawl(cfg);

        client.reset();
        client.when(request(PATH)).respond(response().withStatusCode(404));
        var gone = crawl(cfg);

        assertThat(deleted(gone)).containsAll(first);
    }
}
