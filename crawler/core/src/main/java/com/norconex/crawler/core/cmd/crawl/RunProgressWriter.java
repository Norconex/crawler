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
package com.norconex.crawler.core.cmd.crawl;

import java.io.BufferedWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.event.Event;
import com.norconex.commons.lang.event.EventListener;
import com.norconex.commons.lang.event.EventManager;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.session.CrawlerSession;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes crawl progress as newline-delimited JSON when the
 * <code>runProgressFile</code> system property names a file to write it to:
 *
 * <pre>
 *     -DrunProgressFile=/var/run/my-crawl/progress.ndjson
 * </pre>
 *
 * <p>
 * Like <code>-DenableJMX=true</code> and
 * {@link RunSummaryWriter#SYS_PROP_RUN_SUMMARY_FILE}, this is a system
 * property rather than a configuration setting, because the caller that wants
 * the file is the one that launched the JVM &mdash; a supervising process, a
 * scheduler, a CI job &mdash; and it should not have to edit the crawl
 * configuration to ask for it. Nothing is written when the property is absent,
 * and nothing about the crawl configuration changes when it is present.
 * </p>
 *
 * <h2>What it writes</h2>
 *
 * <p>
 * One JSON object per line, of two kinds:
 * </p>
 *
 * <pre>
 * {"type":"progress","crawlerId":"x","at":"2026-09-12T04:10:00Z","elapsedMs":12345,
 *  "queued":42,"processing":2,"processed":1203,"baseline":5000}
 * {"type":"heartbeat", ... same fields ... }
 * </pre>
 *
 * <p>
 * A <b>progress</b> line is written only when the numbers actually changed
 * since the last one, so a crawl that is politely waiting between requests
 * stays quiet. A <b>heartbeat</b> line is written when nothing has changed for
 * a while, which is what separates "slow" from "stuck" for a supervisor that
 * can otherwise only see that the process is alive.
 * </p>
 *
 * <h2>Why sampling rather than listening</h2>
 *
 * <p>
 * The counts come from {@link com.norconex.crawler.core.metrics.CrawlerMetrics}
 * on a timer, not from an event fired per document. A crawl of a local file
 * system with several threads can finalize thousands of documents a second,
 * and a writer on that path would put file I/O in the crawl loop and produce a
 * volume of output nobody can consume. Sampling costs nothing per document and
 * bounds the output by wall-clock instead of by crawl speed &mdash; the faster
 * the crawl, the more it compresses.
 * </p>
 *
 * <p>
 * Note that in a clustered crawl each node writes its own file, since each has
 * its own view to report.
 * </p>
 */
@Slf4j
public class RunProgressWriter implements EventListener<Event> {

    /** System property naming the file to write crawl progress to. */
    public static final String SYS_PROP_RUN_PROGRESS_FILE = "runProgressFile";

    /** How often the counters are sampled. Optional, as a duration string. */
    public static final String SYS_PROP_RUN_PROGRESS_INTERVAL =
            "runProgressInterval";

    /** How long without change before a heartbeat is written. Optional. */
    public static final String SYS_PROP_RUN_HEARTBEAT_INTERVAL =
            "runProgressHeartbeatInterval";

    private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(2);
    private static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(30);

    private final Path target;
    private final Duration interval;
    private final Duration heartbeat;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private ScheduledExecutorService scheduler;
    private Writer writer;
    private CrawlerSession session;
    private long startedAt;
    private long lastWriteAt;
    private Counts lastWritten;
    private boolean broken;

    /**
     * Event counts as they stood when this crawl began.
     * <p>
     * The crawler's own event-count store accumulates across runs sharing a
     * crawl store &mdash; its execution summary says "incl. resumed" for
     * exactly this reason &mdash; while the queued/processed gauges are this
     * run's. Reporting both raw would put two different time bases in one
     * record, so what is written is the difference from here.
     * </p>
     */
    private Map<String, Long> eventCountsAtStart = Map.of();

    RunProgressWriter(Path target, Duration interval, Duration heartbeat) {
        this.target = target;
        this.interval = interval;
        this.heartbeat = heartbeat;
    }

    /**
     * Registers a progress writer on the given event manager if the system
     * property asks for one.
     * <p>
     * Registering here rather than through the crawl configuration is the
     * point: the crawler's configurable surface is unchanged, so a caller that
     * compiles configurations does not have to know this exists.
     * </p>
     *
     * @param eventManager the event manager to register with
     */
    public static void registerIfRequested(EventManager eventManager) {
        var file = System.getProperty(SYS_PROP_RUN_PROGRESS_FILE);
        if (StringUtils.isBlank(file)) {
            return;
        }
        eventManager.addListener(new RunProgressWriter(
                Path.of(file),
                durationProperty(SYS_PROP_RUN_PROGRESS_INTERVAL,
                        DEFAULT_INTERVAL),
                durationProperty(SYS_PROP_RUN_HEARTBEAT_INTERVAL,
                        DEFAULT_HEARTBEAT)));
    }

    private static Duration durationProperty(String name, Duration fallback) {
        var value = System.getProperty(name);
        if (StringUtils.isBlank(value)) {
            return fallback;
        }
        try {
            return Duration.parse(value);
        } catch (Exception e) {
            LOG.warn("Ignoring -D{}={}: not an ISO-8601 duration (e.g. PT5S).",
                    name, value);
            return fallback;
        }
    }

    @Override
    public void accept(Event event) {
        if (!(event instanceof CrawlerEvent crawlerEvent)) {
            return;
        }
        if (CrawlerEvent.CRAWLER_CRAWL_BEGIN.equals(event.getName())) {
            start(crawlerEvent.getCrawlSession());
        } else if (CrawlerEvent.CRAWLER_CRAWL_END.equals(event.getName())) {
            stop();
        }
    }

    synchronized void start(CrawlerSession crawlSession) {
        if (crawlSession == null || scheduler != null) {
            return;
        }
        session = crawlSession;
        startedAt = System.currentTimeMillis();
        try {
            // Copied, not referenced: getEventCounts() hands back the live
            // map, which keeps moving.
            eventCountsAtStart = new HashMap<>(
                    crawlSession.getCrawlContext().getMetrics()
                            .getEventCounts());
        } catch (Exception e) {
            LOG.debug("Could not read the event counts at crawl start.", e);
            eventCountsAtStart = Map.of();
        }
        try {
            var parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // Appended rather than truncated: a resumed crawl writing into an
            // existing run directory should add to the record, not erase what
            // the previous attempt reported.
            writer = new BufferedWriter(Files.newBufferedWriter(
                    target, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND));
        } catch (Exception e) {
            LOG.warn("Could not open the progress file {}: {}",
                    target, e.toString());
            broken = true;
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "crawler-progress");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(
                this::sample,
                interval.toMillis(),
                interval.toMillis(),
                TimeUnit.MILLISECONDS);
        LOG.info("Writing crawl progress to {} (every {}, heartbeat {})",
                target, interval, heartbeat);
    }

    synchronized void sample() {
        if (broken || writer == null) {
            return;
        }
        try {
            var counts = currentCounts();
            var now = System.currentTimeMillis();
            if (!Objects.equals(counts, lastWritten)) {
                write("progress", counts, now);
            } else if (now - lastWriteAt >= heartbeat.toMillis()) {
                // Nothing moved, but the crawl is still running. Saying so is
                // what makes "stuck" distinguishable from "slow" to something
                // that can otherwise only see that the process is alive.
                write("heartbeat", counts, now);
            }
        } catch (Exception e) {
            // A progress file is never worth failing a crawl over, and a
            // failing writer that keeps being retried every couple of seconds
            // would bury the real log.
            LOG.warn("Could not write crawl progress, giving up on it: {}",
                    e.toString());
            broken = true;
            closeQuietly();
        }
    }

    private Counts currentCounts() {
        var metrics = session.getCrawlContext().getMetrics();
        return new Counts(
                metrics.getQueuedCount(),
                metrics.getProcessingCount(),
                metrics.getProcessedCount(),
                metrics.getBaselineCount(),
                eventCountsThisRun(metrics.getEventCounts()));
    }

    /**
     * This run's event counts, as differences from where they stood when the
     * crawl began.
     * <p>
     * Every event the crawler fires is counted by name, so this is whatever
     * happened rather than a selection somebody made in advance: a new event
     * type shows up as a new key, with nothing to change here or in whatever
     * reads it.
     * </p>
     */
    private Map<String, Long> eventCountsThisRun(Map<String, Long> current) {
        Map<String, Long> thisRun = new TreeMap<>();
        current.forEach((name, total) -> {
            var delta = total - eventCountsAtStart.getOrDefault(name, 0L);
            if (delta > 0) {
                thisRun.put(name, delta);
            }
        });
        return thisRun;
    }

    private void write(String type, Counts counts, long now) throws Exception {
        var record = new ProgressRecord(
                type,
                session.getCrawlContext().getId(),
                Instant.ofEpochMilli(now),
                now - startedAt,
                counts.queued(),
                counts.processing(),
                counts.processed(),
                counts.baseline(),
                counts.eventCounts());
        writer.write(jsonMapper.writeValueAsString(record));
        writer.write('\n');
        // Flushed per record on purpose: these are seconds apart at most, and
        // a reader tailing the file is waiting for them.
        writer.flush();
        lastWritten = counts;
        lastWriteAt = now;
    }

    synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (!broken && writer != null) {
            try {
                // One last line, so the file ends on the final numbers rather
                // than on whatever the last tick happened to catch.
                write("progress", currentCounts(), System.currentTimeMillis());
            } catch (Exception e) {
                LOG.warn("Could not write the final progress record: {}",
                        e.toString());
            }
        }
        closeQuietly();
    }

    private void closeQuietly() {
        if (writer != null) {
            try {
                writer.close();
            } catch (Exception e) {
                LOG.debug("Could not close the progress file.", e);
            }
            writer = null;
        }
    }

    /**
     * The sampled numbers, compared as a whole to detect change.
     * <p>
     * Two kinds sit here, and they are not interchangeable. The four longs are
     * gauges: current state, read off the ledger, answering how far along the
     * crawl is. They cannot be derived from event counts &mdash; a cumulative
     * count of queueings says nothing about how deep the queue is now. The map
     * is counters: what has happened, by event name.
     * </p>
     */
    private record Counts(
            long queued,
            long processing,
            long processed,
            long baseline,
            Map<String, Long> eventCounts) {
    }

    /**
     * One line of the progress file.
     *
     * @param type       {@code progress} or {@code heartbeat}
     * @param crawlerId  the crawler this concerns
     * @param at         when the sample was taken
     * @param elapsedMs  milliseconds since the crawl began
     * @param queued     references waiting to be processed. A reference is a
     *                   ledger entry, not necessarily a document: a folder
     *                   traversed for its children is counted here too
     * @param processing references being processed right now
     * @param processed  references processed so far this run
     * @param baseline   references from the previous run not yet seen again
     * @param eventCounts what happened this run, by event name, as
     *                   differences from where the counts stood when the
     *                   crawl began. Every event the crawler fires is counted,
     *                   so this is not a selection: ask it for
     *                   {@code COMMITTER_UPSERT_END} to get documents
     *                   committed, and a new event type appears as a new key
     *                   with nothing to change here
     */
    public record ProgressRecord(
            String type,
            String crawlerId,
            Instant at,
            long elapsedMs,
            long queued,
            long processing,
            long processed,
            long baseline,
            Map<String, Long> eventCounts) {
    }
}
