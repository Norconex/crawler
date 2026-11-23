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
package com.norconex.crawler.core.cmd.crawl.pipeline.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.committer.core.UpsertRequest;
import com.norconex.committer.core.impl.MemoryCommitter;
import com.norconex.commons.lang.config.Configurable;
import com.norconex.commons.lang.event.Event;
import com.norconex.crawler.core.Crawler;
import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.fetch.FetchException;
import com.norconex.crawler.core.fetch.FetchRequest;
import com.norconex.crawler.core.mocks.fetch.MockFetchResponse;
import com.norconex.crawler.core.mocks.fetch.MockFetcher;
import com.norconex.crawler.core.test.CrawlerTestDriver;

/**
 * A document taking longer than the document timeout is given up on: it is
 * recorded as an error, never sent to committers, and the crawl goes on.
 */
@Timeout(60)
class DocumentTimeoutCrawlTest {

    // Lets threads stuck on "stuck-" references go, at the end of a test.
    private static final AtomicBoolean RELEASE = new AtomicBoolean();

    @TempDir
    private Path tempDir;

    @AfterEach
    void tearDown() {
        RELEASE.set(true);
    }

    @Test
    void testSlowDocumentsAreGivenUpOn() {
        RELEASE.set(false);
        // Interruptible: the fetch ends as soon as it is given up on.
        var result = crawl(2, List.of("slow-1", "slow-2", "ok-1"),
                new SlowFetcher());

        assertThat(result.rejectedErrors)
                .containsExactlyInAnyOrder("slow-1", "slow-2");
        assertThat(result.committed).containsExactly("ok-1");
    }

    @Test
    void testStuckThreadIsReplaced() {
        RELEASE.set(false);
        // Ignores interrupts: its thread is replaced, the other documents
        // are still processed.
        var result = crawl(2, List.of("stuck-1", "ok-1", "ok-2", "ok-3"),
                new SlowFetcher());

        assertThat(result.rejectedErrors).containsExactly("stuck-1");
        assertThat(result.committed)
                .containsExactlyInAnyOrder("ok-1", "ok-2", "ok-3");
    }

    @Test
    void testCrawlStopsWhenEveryThreadIsStuck() {
        RELEASE.set(false);
        var result = crawl(1, List.of("stuck-1", "ok-1"), new SlowFetcher());

        assertThat(result.rejectedErrors).containsExactly("stuck-1");
        assertThat(result.events).contains(
                CrawlerEvent.CRAWLER_STOP_REQUEST_BEGIN);
        assertThat(result.committed).doesNotContain("stuck-1");
    }

    private CrawlResult crawl(
            int numThreads, List<String> refs, MockFetcher fetcher) {
        var committer = new MemoryCommitter();
        var result = new CrawlResult();
        var config = new CrawlerConfig()
                .setId("doc-timeout-" + numThreads + "-" + refs.size())
                .setWorkDir(tempDir)
                .setStartReferences(refs)
                .setFetchers(List.of(fetcher))
                .setCommitters(List.of(committer))
                .setNumThreads(numThreads)
                .setIdleTimeout(Duration.ofMillis(200))
                // Above the first document's importer warm-up time.
                .setDocumentTimeout(Duration.ofSeconds(3));
        config.addEventListener((Event event) -> {
            result.events.add(event.getName());
            if (event.is(CrawlerEvent.REJECTED_ERROR)
                    && event instanceof CrawlerEvent ce
                    && ce.getCrawlEntry() != null) {
                result.rejectedErrors.add(ce.getCrawlEntry().getReference());
            }
        });

        new Crawler(CrawlerTestDriver.create(), config).crawl();

        result.committed.addAll(committer.getUpsertRequests().stream()
                .map(UpsertRequest::getReference)
                .toList());
        return result;
    }

    private static final class CrawlResult {
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final List<String> rejectedErrors =
                new CopyOnWriteArrayList<>();
        private final List<String> committed = new CopyOnWriteArrayList<>();
    }

    /**
     * Takes 30 seconds on "slow-" references (interruptible), and until
     * released on "stuck-" ones (ignoring interrupts).
     */
    static final class SlowFetcher extends MockFetcher {
        SlowFetcher() {
            Configurable.configure(this, cfg -> cfg.setDelay(null));
        }

        @Override
        public MockFetchResponse fetch(FetchRequest req)
                throws FetchException {
            var ref = req.getDoc().getReference();
            if (ref.startsWith("slow-")) {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new FetchException("Interrupted.", e);
                }
            } else if (ref.startsWith("stuck-")) {
                while (!RELEASE.get()) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        // ignored on purpose: a thread that won't let go
                    }
                }
            }
            return super.fetch(req);
        }
    }
}
