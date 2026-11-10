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
package com.norconex.crawler.core.cmd.crawl.pipeline.bootstrap.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.commons.lang.Sleeper;
import com.norconex.commons.lang.event.Event;
import com.norconex.commons.lang.event.EventListener;
import com.norconex.crawler.core.CrawlerDriver;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.test.CrawlerTestDriver;
import com.norconex.crawler.core.test.CrawlerTestHarness;
import com.norconex.crawler.core.test.CrawlerTestInstrument;

/**
 * With asynchronous start-reference queueing, the crawl must wait for the
 * queueing to be over, even when the queue runs empty in the meantime (e.g.,
 * while a large sitemap is being fetched).
 */
@Timeout(60)
class AsyncStartRefsQueueingTest {

    /** Longer than the crawler's idle timeout plus its queueing wait. */
    private static final long PAUSE_MS = 4_000;

    // Shared with the driver, which the harness instantiates by class.
    private static final CountDownLatch FIRST_IMPORTED = new CountDownLatch(1);
    private static final List<String> IMPORTED = new CopyOnWriteArrayList<>();

    @Test
    void testCrawlWaitsForAsyncQueueingToBeOver(@TempDir Path tempDir)
            throws Exception {
        var instrument = new CrawlerTestInstrument()
                .setDriverSupplierClass(SlowEnqueuerDriver.class)
                .setRecordEvents(true)
                .setWorkDir(tempDir)
                .setNewJvm(false)
                .setClustered(false)
                .setConfigModifier(cfg -> {
                    cfg.setId("test-async-start-refs");
                    cfg.setStartReferencesAsync(true);
                    cfg.setNumThreads(1);
                    cfg.setIdleTimeout(Duration.ofMillis(500));
                    cfg.addEventListener(new ImportRecorder());
                });

        try (var harness = new CrawlerTestHarness(instrument)) {
            harness.launchSync("node1");
        }

        assertThat(IMPORTED)
                .as("references queued after a pause are crawled too")
                .containsExactly("ref-first", "ref-after-pause");
    }

    /**
     * Queues one reference, waits for it to be imported (the queue is then
     * empty), pauses, then queues another one.
     */
    static class SlowEnqueuer implements ReferenceEnqueuer {
        @Override
        public int enqueue(QueueBootstrapContext ctx) {
            ctx.queue("ref-first");
            try {
                FIRST_IMPORTED.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 1;
            }
            Sleeper.sleepMillis(PAUSE_MS);
            ctx.queue("ref-after-pause");
            return 2;
        }
    }

    public static class SlowEnqueuerDriver implements Supplier<CrawlerDriver> {
        @Override
        public CrawlerDriver get() {
            return CrawlerTestDriver.builder()
                    .referenceEnqueuers(List.of(new SlowEnqueuer()))
                    .build();
        }
    }

    static class ImportRecorder implements EventListener<Event> {
        @Override
        public void accept(Event event) {
            if (event.is(CrawlerEvent.DOCUMENT_IMPORTED)
                    && event instanceof CrawlerEvent crawlerEvent) {
                IMPORTED.add(crawlerEvent.getCrawlEntry().getReference());
                FIRST_IMPORTED.countDown();
            }
        }
    }
}
