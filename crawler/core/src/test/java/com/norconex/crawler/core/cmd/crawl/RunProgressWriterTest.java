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

import static com.norconex.crawler.core.cmd.crawl.RunProgressWriter.SYS_PROP_RUN_PROGRESS_FILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.commons.lang.event.EventManager;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.metrics.CrawlerMetrics;
import com.norconex.crawler.core.session.CrawlerSession;

import tools.jackson.databind.json.JsonMapper;

class RunProgressWriterTest {

    private static final Duration NEVER = Duration.ofHours(1);
    private static final Duration ALWAYS = Duration.ZERO;

    @TempDir
    private Path tempDir;

    private final CrawlerMetrics metrics = mock(CrawlerMetrics.class);

    @AfterEach
    void clearProperties() {
        System.clearProperty(SYS_PROP_RUN_PROGRESS_FILE);
    }

    private CrawlerSession session() {
        var ctx = mock(CrawlerContext.class);
        when(ctx.getId()).thenReturn("test-crawler");
        when(ctx.getMetrics()).thenReturn(metrics);
        var session = mock(CrawlerSession.class);
        when(session.getCrawlContext()).thenReturn(ctx);
        return session;
    }

    private void counts(long queued, long processing, long processed) {
        when(metrics.getQueuedCount()).thenReturn(queued);
        when(metrics.getProcessingCount()).thenReturn(processing);
        when(metrics.getProcessedCount()).thenReturn(processed);
        when(metrics.getBaselineCount()).thenReturn(0L);
        when(metrics.getEventCounts()).thenReturn(new HashMap<>());
    }

    private void events(Map<String, Long> eventCounts) {
        when(metrics.getEventCounts()).thenReturn(new HashMap<>(eventCounts));
    }

    private List<String> lines(Path file) {
        try {
            return Files.exists(file) ? Files.readAllLines(file) : List.of();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String typeOf(String line) {
        return JsonMapper.builder().build()
                .readTree(line).get("type").asString();
    }

    // -----------------------------------------------------------------
    // Registration
    // -----------------------------------------------------------------

    @Test
    void registersNothingWithoutTheSystemProperty() {
        var eventManager = new EventManager();

        RunProgressWriter.registerIfRequested(eventManager);

        assertThat(eventManager.getListenerCount()).isZero();
    }

    @Test
    void registersAListenerWhenAsked() {
        System.setProperty(SYS_PROP_RUN_PROGRESS_FILE,
                tempDir.resolve("progress.ndjson").toString());
        var eventManager = new EventManager();

        RunProgressWriter.registerIfRequested(eventManager);

        assertThat(eventManager.getListenerCount()).isOne();
    }

    // -----------------------------------------------------------------
    // Change-driven writing
    // -----------------------------------------------------------------

    @Test
    void writesAProgressLineWhenTheNumbersMove() {
        var file = tempDir.resolve("progress.ndjson");
        var writer = new RunProgressWriter(file, NEVER, NEVER);
        counts(10, 1, 5);

        writer.start(session());
        writer.sample();

        assertThat(lines(file)).hasSize(1);
        var json = JsonMapper.builder().build().readTree(lines(file).get(0));
        assertThat(json.get("type").asString()).isEqualTo("progress");
        assertThat(json.get("crawlerId").asString()).isEqualTo("test-crawler");
        assertThat(json.get("queued").asLong()).isEqualTo(10);
        assertThat(json.get("processing").asLong()).isEqualTo(1);
        assertThat(json.get("processed").asLong()).isEqualTo(5);
        // Never a reference, never content: this file is read by a supervisor
        // and what it carries is what leaves the customer's network.
        assertThat(lines(file).get(0))
                .doesNotContain("reference")
                .doesNotContain("http");
    }

    @Test
    void staysQuietWhenNothingChanged() {
        var file = tempDir.resolve("progress.ndjson");
        // A heartbeat that never comes due, so silence is purely the
        // change-driven behaviour: a crawl politely waiting between requests
        // must not fill a disk with identical lines.
        var writer = new RunProgressWriter(file, NEVER, NEVER);
        counts(10, 1, 5);

        writer.start(session());
        writer.sample();
        writer.sample();
        writer.sample();

        assertThat(lines(file)).hasSize(1);
    }

    @Test
    void writesOneLinePerChangeRatherThanPerSample() {
        var file = tempDir.resolve("progress.ndjson");
        var writer = new RunProgressWriter(file, NEVER, NEVER);

        writer.start(session());
        counts(10, 1, 5);
        writer.sample();
        writer.sample();
        counts(9, 1, 6);
        writer.sample();
        writer.sample();

        assertThat(lines(file)).hasSize(2);
    }

    // -----------------------------------------------------------------
    // Event counts: everything that happened, not a selection
    // -----------------------------------------------------------------

    @Test
    void reportsEveryEventCountRatherThanAChosenFew() {
        var file = tempDir.resolve("progress.ndjson");
        var writer = new RunProgressWriter(file, NEVER, NEVER);
        counts(0, 0, 403);

        writer.start(session());

        // A folder-bearing crawl: 403 references processed, 400 of which
        // became documents. Both numbers are here without anyone having
        // picked them, and a new event type would arrive as a new key.
        events(Map.of(
                "DOCUMENT_QUEUED", 403L,
                "DOCUMENT_IMPORTED", 400L,
                "COMMITTER_UPSERT_END", 400L,
                "SOME_FUTURE_EVENT", 7L));
        writer.sample();

        var json = JsonMapper.builder().build().readTree(lines(file).get(0));
        var eventCounts = json.get("sessionEventCounts");
        assertThat(eventCounts.get("COMMITTER_UPSERT_END").asLong())
                .as("documents committed, without a field for it")
                .isEqualTo(400);
        assertThat(eventCounts.get("DOCUMENT_QUEUED").asLong())
                .as("references, which include folders")
                .isEqualTo(403);
        assertThat(eventCounts.get("SOME_FUTURE_EVENT").asLong())
                .as("an event this build never heard of still gets through")
                .isEqualTo(7);
    }

    @Test
    void aResumedCrawlReportsSessionTotalsAndThisRunsShare() {
        var file = tempDir.resolve("progress.ndjson");
        var writer = new RunProgressWriter(file, NEVER, NEVER);
        counts(0, 0, 400);

        // This process starts on a crawl that already committed 300 documents
        // before being stopped. The store is session-scoped, so those 300 are
        // still this session's -- the work was not done twice.
        events(Map.of("COMMITTER_UPSERT_END", 300L));
        writer.start(session());

        // It then commits 100 more.
        events(Map.of("COMMITTER_UPSERT_END", 400L));
        writer.sample();

        var json = JsonMapper.builder().build().readTree(lines(file).get(0));
        assertThat(json.get("sessionEventCounts")
                .get("COMMITTER_UPSERT_END").asLong())
                        .as("the crawl has committed 400 documents")
                        .isEqualTo(400);
        assertThat(json.get("runEventCounts")
                .get("COMMITTER_UPSERT_END").asLong())
                        .as("this run committed 100 of them")
                        .isEqualTo(100);
    }

    @Test
    void withoutAResumeTheTwoMapsAgree() {
        var file = tempDir.resolve("progress.ndjson");
        var writer = new RunProgressWriter(file, NEVER, NEVER);
        counts(0, 0, 50);

        writer.start(session()); // nothing counted yet
        events(Map.of("COMMITTER_UPSERT_END", 50L));
        writer.sample();

        var json = JsonMapper.builder().build().readTree(lines(file).get(0));
        assertThat(json.get("runEventCounts"))
                .as("a crawl that was never interrupted has one set of numbers")
                .isEqualTo(json.get("sessionEventCounts"));
    }

    @Test
    void anEventCountMovingIsItselfAChangeWorthReporting() {
        var file = tempDir.resolve("progress.ndjson");
        var writer = new RunProgressWriter(file, NEVER, NEVER);
        counts(5, 1, 10);
        events(Map.of("COMMITTER_UPSERT_END", 0L));

        writer.start(session());
        writer.sample();

        // Gauges identical, but documents were committed in between. A
        // console told "nothing happened" would be wrong.
        events(Map.of("COMMITTER_UPSERT_END", 25L));
        writer.sample();

        assertThat(lines(file)).hasSize(2);
    }

    // -----------------------------------------------------------------
    // Heartbeat: what separates "slow" from "stuck"
    // -----------------------------------------------------------------

    @Test
    void writesAHeartbeatWhenNothingChangedForLongEnough() {
        var file = tempDir.resolve("progress.ndjson");
        var writer = new RunProgressWriter(file, NEVER, ALWAYS);
        counts(10, 1, 5);

        writer.start(session());
        writer.sample(); // progress: first sample always differs
        writer.sample(); // unchanged, but the heartbeat is due

        var written = lines(file);
        assertThat(written).hasSize(2);
        assertThat(typeOf(written.get(0))).isEqualTo("progress");
        assertThat(typeOf(written.get(1))).isEqualTo("heartbeat");
    }

    @Test
    void aChangePreemptsTheHeartbeat() {
        var file = tempDir.resolve("progress.ndjson");
        var writer = new RunProgressWriter(file, NEVER, ALWAYS);

        writer.start(session());
        counts(10, 1, 5);
        writer.sample();
        counts(9, 1, 6);
        writer.sample();

        var written = lines(file);
        assertThat(written).hasSize(2);
        assertThat(typeOf(written.get(1))).isEqualTo("progress");
    }

    // -----------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------

    @Test
    void endingTheCrawlWritesTheFinalNumbers() {
        var file = tempDir.resolve("progress.ndjson");
        var writer = new RunProgressWriter(file, NEVER, NEVER);
        counts(10, 1, 5);

        writer.start(session());
        writer.sample();
        counts(0, 0, 15);
        writer.stop();

        var written = lines(file);
        // The file ends on the real final numbers rather than on whatever the
        // last tick happened to catch.
        assertThat(written).hasSize(2);
        var last = JsonMapper.builder().build()
                .readTree(written.get(written.size() - 1));
        assertThat(last.get("processed").asLong()).isEqualTo(15);
        assertThat(last.get("queued").asLong()).isZero();
    }

    @Test
    void appendsRatherThanErasingAPreviousAttempt() {
        var file = tempDir.resolve("progress.ndjson");

        var first = new RunProgressWriter(file, NEVER, NEVER);
        counts(10, 1, 5);
        first.start(session());
        first.sample();
        first.stop();
        var afterFirst = lines(file).size();

        var second = new RunProgressWriter(file, NEVER, NEVER);
        counts(3, 0, 12);
        second.start(session());
        second.sample();
        second.stop();

        assertThat(lines(file)).hasSizeGreaterThan(afterFirst);
    }

    @Test
    void aFailingWriterNeverFailsTheCrawl() {
        var file = tempDir.resolve("progress.ndjson");
        var writer = new RunProgressWriter(file, NEVER, NEVER);
        when(metrics.getQueuedCount())
                .thenThrow(new IllegalStateException("metrics are gone"));

        writer.start(session());

        // A progress file is never worth failing a crawl over.
        assertThatNoException().isThrownBy(writer::sample);
        assertThatNoException().isThrownBy(writer::stop);
    }

    @Test
    void anUnwritableTargetIsNotFatal() {
        // A directory where a file should be: opening it will fail.
        var file = tempDir.resolve("a-directory");
        try {
            Files.createDirectory(file);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        var writer = new RunProgressWriter(file, NEVER, NEVER);

        assertThatNoException().isThrownBy(() -> {
            writer.start(session());
            writer.sample();
            writer.stop();
        });
    }
}
