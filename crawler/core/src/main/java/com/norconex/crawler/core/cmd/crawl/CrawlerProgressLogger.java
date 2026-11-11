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
package com.norconex.crawler.core.cmd.crawl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.StopWatch;

import com.norconex.commons.lang.time.DurationFormatter;
import com.norconex.commons.lang.time.DurationUnit;
import com.norconex.crawler.core.cluster.pipeline.PipelineProgress;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.metrics.CrawlerMetrics;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * Logs useful information about the crawler execution progress.
 */
@Slf4j
@EqualsAndHashCode
@ToString
public class CrawlerProgressLogger {

    private final StopWatch stopWatch = new StopWatch();

    private Duration minLoggingInterval;
    private final CrawlerMetrics metrics;

    // Just so we can show the delta between each log entry
    private final Counts prevCounts = new Counts();
    private long prevElapsed;

    private ScheduledExecutorService execService;
    private volatile boolean stopTrackingRequested;

    private Runnable stopCheckCallback;

    private final DurationFormatter durationFormatter = new DurationFormatter()
            .withOuterLastSeparator(" and ")
            .withOuterSeparator(", ")
            .withUnitPrecision(2)
            .withLowestUnit(DurationUnit.SECOND);
    private final NumberFormat intFormatter = NumberFormat.getIntegerInstance();

    // Optional supplier to fetch current pipeline progress (coordinator view)
    private final Supplier<PipelineProgress> pipelineProgressSupplier;

    // Minimum 1 second
    public CrawlerProgressLogger(CrawlerContext ctx) {
        this(ctx, null);
    }

    public CrawlerProgressLogger(CrawlerContext ctx,
            Supplier<PipelineProgress> pipelineProgressSupplier) {
        metrics = ctx.getMetrics();
        var minInterval = ctx.getCrawlConfig().getMinProgressLoggingInterval();
        if (minInterval == null || minInterval.getSeconds() < 1) {
            minLoggingInterval = null;
        } else {
            minLoggingInterval = minInterval;
        }
        this.pipelineProgressSupplier = pipelineProgressSupplier;
    }

    public void setStopCheckCallback(Runnable callback) {
        stopCheckCallback = callback;
    }

    public void start() {
        stopTrackingRequested = false;
        if (minLoggingInterval != null && LOG.isInfoEnabled()) {
            execService = Executors.newSingleThreadScheduledExecutor();
            execService.scheduleWithFixedDelay(() -> {
                if (stopTrackingRequested) {
                    return;
                }
                logProgress();
            }, 0, minLoggingInterval.toMillis(), TimeUnit.MILLISECONDS);
        }
        stopWatch.reset();
        stopWatch.start();
        prevCounts.applyMetrics(metrics);

        while (!stopTrackingRequested) {
            if (stopCheckCallback != null) {
                stopCheckCallback.run();
            }
            try {
                Thread.sleep(100); // Small delay to avoid busy-waiting
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupt
                break;
            }
        }
    }

    public void stop() {
        if (stopTrackingRequested) {
            return;
        }
        LOG.info("Stopping crawl progress logger...");
        stopTrackingRequested = true;
        if (!stopWatch.isStopped()) {
            stopWatch.stop();
        }
        if (execService != null) {
            try {
                execService.shutdownNow();
                if (!execService.awaitTermination(2, TimeUnit.SECONDS)) {
                    LOG.warn("Progress logger executor did not terminate "
                            + "within timeout.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("Interrupted while waiting for progress logger "
                        + "executor to terminate.");
            } finally {
                execService = null;
            }
        }
        metrics.flush();
        logProgress();
        LOG.info("Execution Summary:{}", getExecutionSummary());
    }

    public String getExecutionSummary() {
        metrics.flush();
        var elapsed = stopWatch.getTime();
        var processedCount = metrics.getProcessedCount();
        var b = new StringBuilder()
                .append("\nTotal processed:   ")
                .append(processedCount)
                .append("\nSince (re)start:")
                .append("\n  Crawl duration:  ")
                .append(durationFormatter.format(elapsed))
                .append("\n  Avg. throughput: ")
                .append(divideDownStr(processedCount * 1000, elapsed, 1))
                .append(" processed/seconds")
                .append("\n  Event counts (incl. resumed):");

        metrics.getEventCounts()
                .entrySet()
                .stream()
                .sorted(Comparator.comparing(Entry<String, Long>::getKey))
                .forEach(en -> b.append("\n    ")
                        .append(StringUtils.rightPad(en.getKey() + ": ", 32))
                        .append(intFormatter.format(en.getValue())));
        return b.toString();
    }

    synchronized void logProgress() {

        // If not enough time has elapsed, return
        var elapsed = stopWatch.getTime();
        if (elapsed < prevElapsed + minLoggingInterval.toMillis()) {
            return;
        }

        // OK, log it
        var freshCounts = new Counts().applyMetrics(metrics);

        var msg = infoMessage(elapsed, freshCounts);
        if (LOG.isDebugEnabled()) {
            // if debugging, compute and show more stats
            LOG.info("{}{}", msg, debugMessage(elapsed, freshCounts));
        } else {
            LOG.info(msg);
        }
        prevCounts.applyMetrics(metrics);
        prevElapsed = elapsed;
    }

    private String infoMessage(long elapsed, Counts counts) {
        var deltas = prevCounts.deltas(counts);

        var elapsedTime = durationFormatter.format(stopWatch.getTime());
        var throughput = divideDownStr(
                (counts.processed - prevCounts.processed) * 1000,
                elapsed - prevElapsed,
                1);
        // Show current queue size and delta, e.g. '5 queued (+5)'
        var base = String.format(
                "%s processed (%s) | %s queued (%s) | %s processing (%s) "
                        + "| %s processed/sec | %s elapsed",
                counts.processed,
                plusMinus(deltas.processed),
                counts.queued,
                plusMinus(deltas.queued),
                counts.processing,
                plusMinus(deltas.processing),
                throughput,
                elapsedTime);
        var stepInfo = stepProgressMessage();
        return StringUtils.isBlank(stepInfo) ? base
                : base + " | " + stepInfo;
    }

    private String stepProgressMessage() {
        if (pipelineProgressSupplier == null) {
            return null;
        }
        try {
            var pp = pipelineProgressSupplier.get();
            if (pp == null || pp.getCurrentStepId() == null) {
                return null;
            }
            // Only show when running and we have a progress value > 0
            var pct = Math.round(pp.getStepProgress() * 100f);
            if (pct <= 0 && (pp.getStatus() == null
                    || !pp.getStatus().isTerminal())) {
                return null;
            }
            var label = pp.getCurrentStepId();
            var idxStr = (pp.getCurrentStepIndex() > 0 || pp.getStepCount() > 0)
                    ? ("step " + (pp.getCurrentStepIndex() + 1) + "/"
                            + pp.getStepCount() + " ")
                    : "";
            var msg = pp.getStepMessage();
            var suffix = StringUtils.isNotBlank(msg) ? (" — " + msg) : "";
            return (idxStr + label + " " + pct + "%").trim() + suffix;
        } catch (Exception e) {
            // Be resilient in logger; never break logging due to
            // supplier issues
            return null;
        }
    }

    private String debugMessage(long elapsed, Counts counts) {
        var totalSoFar = counts.processed + counts.queued;
        var progress = divideDownStr(counts.processed * 100, totalSoFar, 2);
        var remaining = durationFormatter.format(
                divideDown(
                        elapsed * counts.queued,
                        counts.processed,
                        0).longValueExact());
        return String.format(
                " | ≈%s%% complete | ≈%s remaining",
                progress, remaining);
    }

    private String plusMinus(long val) {
        return (val >= 0 ? "+" : "") + intFormatter.format(val);
    }

    private BigDecimal divideDown(long dividend, long divisor, int scale) {
        if (divisor == 0L) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(dividend)
                .divide(BigDecimal.valueOf(divisor), scale, RoundingMode.DOWN)
                .stripTrailingZeros();
    }

    private String divideDownStr(long dividend, long divisor, int scale) {
        return divideDown(dividend, divisor, scale).toPlainString();
    }

    @Getter
    private static class Counts {
        private long queued;
        private long processing;
        private long processed;
        private long baseline;

        private Counts applyMetrics(CrawlerMetrics metrics) {
            queued = metrics.getQueuedCount();
            processing = metrics.getProcessingCount();
            processed = metrics.getProcessedCount();
            baseline = metrics.getBaselineCount();
            return this;
        }

        private Counts deltas(Counts newCounts) {
            var deltas = new Counts();
            deltas.queued = newCounts.queued - queued;
            deltas.processing = newCounts.processing - processing;
            deltas.processed = newCounts.processed - processed;
            deltas.baseline = newCounts.baseline - baseline;
            return deltas;
        }
    }
}
