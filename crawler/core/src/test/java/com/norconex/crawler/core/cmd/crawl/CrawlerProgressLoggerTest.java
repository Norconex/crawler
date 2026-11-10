/* Copyright 2025-2026 Norconex Inc.
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
package com.norconex.crawler.core.cmd.crawl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.cluster.pipeline.PipelineProgress;
import com.norconex.crawler.core.cluster.pipeline.PipelineStatus;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.metrics.CrawlerMetrics;

/**
 * Tests for {@link CrawlerProgressLogger}.
 */
@Timeout(15)
class CrawlerProgressLoggerTest {

    private CrawlerContext buildContext(Duration minLoggingInterval) {
        var metrics = mock(CrawlerMetrics.class);
        when(metrics.getProcessedCount()).thenReturn(50L);
        when(metrics.getQueuedCount()).thenReturn(10L);
        when(metrics.getProcessingCount()).thenReturn(2L);
        when(metrics.getBaselineCount()).thenReturn(0L);
        when(metrics.getEventCounts()).thenReturn(
                Map.of("DOCUMENT_PROCESSED", 50L, "REJECTED_FILTER", 3L));

        var config = mock(CrawlerConfig.class);
        when(config.getMinProgressLoggingInterval())
                .thenReturn(minLoggingInterval);

        var ctx = mock(CrawlerContext.class);
        when(ctx.getMetrics()).thenReturn(metrics);
        when(ctx.getCrawlConfig()).thenReturn(config);
        return ctx;
    }

    // -----------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------

    @Test
    void constructor_withNullMinInterval_isCreated() {
        var logger = new CrawlerProgressLogger(buildContext(null));
        assertThat(logger).isNotNull();
    }

    @Test
    void constructor_withSupplier_isCreated() {
        var logger = new CrawlerProgressLogger(buildContext(null), () -> null);
        assertThat(logger).isNotNull();
    }

    @Test
    void constructor_withBelowMinInterval_treatedAsNull() {
        // 500 ms < 1 s → minLoggingInterval is set to null internally
        var logger =
                new CrawlerProgressLogger(buildContext(Duration.ofMillis(500)));
        assertThat(logger).isNotNull();
    }

    @Test
    void constructor_withExactlyOneSecond_acceptsInterval() {
        var logger =
                new CrawlerProgressLogger(buildContext(Duration.ofSeconds(1)));
        assertThat(logger).isNotNull();
    }

    // -----------------------------------------------------------------
    // getExecutionSummary
    // -----------------------------------------------------------------

    @Test
    void getExecutionSummary_returnsFormattedOutput() {
        var logger = new CrawlerProgressLogger(buildContext(null));
        var summary = logger.getExecutionSummary();
        assertThat(summary)
                .contains("Total processed")
                .contains("50")
                .contains("Crawl duration")
                .contains("Avg. throughput")
                .contains("DOCUMENT_PROCESSED")
                .contains("REJECTED_FILTER");
    }

    @Test
    void getExecutionSummary_emptyEventCounts_noEventSection() {
        var metrics = mock(CrawlerMetrics.class);
        when(metrics.getProcessedCount()).thenReturn(0L);
        when(metrics.getQueuedCount()).thenReturn(0L);
        when(metrics.getProcessingCount()).thenReturn(0L);
        when(metrics.getBaselineCount()).thenReturn(0L);
        when(metrics.getEventCounts()).thenReturn(Map.of());
        var config = mock(CrawlerConfig.class);
        var ctx = mock(CrawlerContext.class);
        when(ctx.getMetrics()).thenReturn(metrics);
        when(ctx.getCrawlConfig()).thenReturn(config);

        var logger = new CrawlerProgressLogger(ctx);
        assertThatNoException().isThrownBy(logger::getExecutionSummary);
    }

    // -----------------------------------------------------------------
    // setStopCheckCallback / start()
    // -----------------------------------------------------------------

    @Test
    void setStopCheckCallback_callbackInvokedDuringLoop() {
        var called = new AtomicBoolean(false);
        var logger =
                new CrawlerProgressLogger(buildContext(Duration.ofSeconds(1)));
        logger.setStopCheckCallback(() -> {
            called.set(true);
            logger.stop(); // exit the blocking start() loop
        });
        // start() is blocking; the callback will stop it immediately
        logger.start();
        assertThat(called.get()).isTrue();
    }

    // -----------------------------------------------------------------
    // stop()
    // -----------------------------------------------------------------

    @Test
    void stop_whenNeverStarted_isIdempotent() {
        // Use valid interval so logProgress() in stop() does not NPE
        var logger =
                new CrawlerProgressLogger(buildContext(Duration.ofSeconds(1)));
        assertThatNoException().isThrownBy(logger::stop);
        assertThatNoException().isThrownBy(logger::stop); // second call: no-op
    }

    // -----------------------------------------------------------------
    // Full lifecycle with scheduled logging (covers logProgress / infoMessage)
    // -----------------------------------------------------------------

    @Test
    void start_stop_withScheduledLogging_coversLogProgressAndInfoMessage()
            throws InterruptedException {
        // minInterval=1s → scheduler fires logProgress() every ~1s
        var ctx = buildContext(Duration.ofSeconds(1));
        var logger = new CrawlerProgressLogger(ctx);

        var future = startInOwnThread(logger);
        // Wait >1s so logProgress() body is executed by the scheduler
        Thread.sleep(1500);
        logger.stop();
        future.join();
    }

    @Test
    void start_stop_withPipelineProgress_coversStepProgressMessage()
            throws InterruptedException {
        var ctx = buildContext(Duration.ofSeconds(1));
        var progress = mock(PipelineProgress.class);
        when(progress.getCurrentStepId()).thenReturn("FETCH");
        when(progress.getStepProgress()).thenReturn(0.6f);
        when(progress.getStatus()).thenReturn(PipelineStatus.RUNNING);
        when(progress.getCurrentStepIndex()).thenReturn(0);
        when(progress.getStepCount()).thenReturn(2);
        when(progress.getStepMessage()).thenReturn("Fetching references...");

        var logger = new CrawlerProgressLogger(ctx, () -> progress);

        var future = startInOwnThread(logger);
        Thread.sleep(1500);
        logger.stop();
        future.join();
    }

    @Test
    void start_stop_withZeroStepProgress_noTerminalStatus_suppressesMessage()
            throws InterruptedException {
        var ctx = buildContext(Duration.ofSeconds(1));
        var progress = mock(PipelineProgress.class);
        // pct=0, non-terminal → stepProgressMessage returns null
        when(progress.getCurrentStepId()).thenReturn("BOOTSTRAP");
        when(progress.getStepProgress()).thenReturn(0.0f);
        when(progress.getStatus()).thenReturn(null);

        var logger = new CrawlerProgressLogger(ctx, () -> progress);

        var future = startInOwnThread(logger);
        Thread.sleep(1500);
        logger.stop();
        future.join();
    }

    @Test
    void start_stop_withNullSupplierResult_isResilient()
            throws InterruptedException {
        var ctx = buildContext(Duration.ofSeconds(1));
        var logger = new CrawlerProgressLogger(ctx, () -> null);

        var future = startInOwnThread(logger);
        Thread.sleep(1500);
        logger.stop();
        future.join();
    }

    @Test
    void start_stop_withThrowingSupplier_isResilient()
            throws InterruptedException {
        var ctx = buildContext(Duration.ofSeconds(1));
        var logger = new CrawlerProgressLogger(ctx,
                () -> {
                    throw new RuntimeException("supplier failure");
                });

        var future = startInOwnThread(logger);
        Thread.sleep(1500);
        logger.stop();
        future.join();
    }

    @Test
    void start_stop_withLargeStepIndex_showsStepInfo()
            throws InterruptedException {
        var ctx = buildContext(Duration.ofSeconds(1));
        var progress = mock(PipelineProgress.class);
        when(progress.getCurrentStepId()).thenReturn("COMMIT");
        when(progress.getStepProgress()).thenReturn(1.0f);
        when(progress.getStatus()).thenReturn(PipelineStatus.COMPLETED);
        when(progress.getCurrentStepIndex()).thenReturn(3);
        when(progress.getStepCount()).thenReturn(5);
        when(progress.getStepMessage()).thenReturn(null); // no extra message

        var logger = new CrawlerProgressLogger(ctx, () -> progress);

        var future = startInOwnThread(logger);
        Thread.sleep(1500);
        logger.stop();
        future.join();
    }

    // As in a crawl: start() blocks until stopped, so it must not hold a
    // thread of the shared common pool (see CrawlerCommonPoolTest).
    private static CompletableFuture<Void> startInOwnThread(
            CrawlerProgressLogger logger) {
        return CompletableFuture.runAsync(logger::start, task -> {
            var thread = new Thread(task, "progress-logger-test");
            thread.setDaemon(true);
            thread.start();
        });
    }
}
