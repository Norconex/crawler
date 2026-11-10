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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.norconex.commons.lang.event.Event;
import com.norconex.commons.lang.event.EventListener;
import com.norconex.crawler.core.Crawler;
import com.norconex.crawler.core.CrawlerDriver;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.test.CrawlerTestDriver;
import com.norconex.crawler.core.test.CrawlerTestHarness;
import com.norconex.crawler.core.test.CrawlerTestInstrument;

/**
 * Queueing of start references stopped part way resumes with the crawl:
 * across the stopped run and the resumed one, every start reference is
 * crawled, and none twice.
 *
 * <p>The stop lands at an exact point: the enqueuer is held right after
 * queueing {@value #STOP_AFTER} references until the stop has been sent.</p>
 */
@Timeout(120)
class StartRefsQueueingStopResumeTest {

    private static final int REF_COUNT = 10;
    private static final int STOP_AFTER = 5;
    private static final long FAILSAFE_SECONDS = 60;

    // Shared with the driver, which the harness instantiates by class.
    private static volatile boolean holdEnqueuer;
    private static volatile CountDownLatch enqueuerHeld;
    private static volatile CountDownLatch stopSent;
    private static final List<String> IMPORTED = new CopyOnWriteArrayList<>();

    @TempDir
    Path tempDir;

    @BeforeEach
    void reset() {
        holdEnqueuer = true;
        enqueuerHeld = new CountDownLatch(1);
        stopSent = new CountDownLatch(1);
        IMPORTED.clear();
    }

    @ParameterizedTest(name = "async={0}")
    @ValueSource(booleans = { false, true })
    void testStoppedQueueingResumes(boolean async) throws Exception {
        var instrument = new CrawlerTestInstrument()
                .setDriverSupplierClass(HeldEnqueuerDriver.class)
                .setRecordEvents(true)
                .setWorkDir(tempDir)
                .setNewJvm(false)
                .setClustered(false)
                .setConfigModifier(cfg -> {
                    cfg.setId("test-queueing-stop-resume");
                    cfg.setStartReferencesAsync(async);
                    cfg.setNumThreads(1);
                    cfg.setIdleTimeout(Duration.ofMillis(500));
                    cfg.addEventListener(new ImportRecorder());
                });

        try (var harness = new CrawlerTestHarness(instrument)) {
            //--- First run, stopped while queueing ---
            var firstRun = harness.launchAsync("node1");
            assertThat(enqueuerHeld.await(FAILSAFE_SECONDS, TimeUnit.SECONDS))
                    .as("queueing reached the stop point")
                    .isTrue();
            new Crawler(new HeldEnqueuerDriver().get(),
                    harness.getFirstNodeConfig()).stop();
            stopSent.countDown();
            firstRun.get(FAILSAFE_SECONDS, TimeUnit.SECONDS);
            var importedFirst = new ArrayList<>(IMPORTED);
            assertThat(importedFirst)
                    .as("the first run stops before queueing is done")
                    .hasSizeLessThanOrEqualTo(STOP_AFTER);

            //--- Second run, resuming ---
            harness.launchSync("node1");
        }

        assertThat(IMPORTED)
                .as("every start reference crawled exactly once overall")
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(allRefs());
    }

    private static List<String> allRefs() {
        return IntStream.range(0, REF_COUNT).mapToObj(i -> "ref-" + i)
                .toList();
    }

    /**
     * Queues the references in order. The first time only, it is held
     * after {@link #STOP_AFTER} of them until the stop was sent.
     */
    static class HeldEnqueuer implements ReferenceEnqueuer {
        @Override
        public int enqueue(QueueBootstrapContext ctx) {
            var cnt = 0;
            for (var ref : allRefs()) {
                if (ctx.isStopRequested()) {
                    break;
                }
                ctx.queue(ref);
                cnt++;
                if (cnt == STOP_AFTER && holdEnqueuer) {
                    holdEnqueuer = false;
                    enqueuerHeld.countDown();
                    try {
                        stopSent.await(FAILSAFE_SECONDS, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            return cnt;
        }
    }

    public static class HeldEnqueuerDriver
            implements Supplier<CrawlerDriver> {
        @Override
        public CrawlerDriver get() {
            return CrawlerTestDriver.builder()
                    .referenceEnqueuers(List.of(new HeldEnqueuer()))
                    .build();
        }
    }

    static class ImportRecorder implements EventListener<Event> {
        @Override
        public void accept(Event event) {
            if (event.is(CrawlerEvent.DOCUMENT_IMPORTED)
                    && event instanceof CrawlerEvent crawlerEvent) {
                IMPORTED.add(crawlerEvent.getCrawlEntry().getReference());
            }
        }
    }
}
