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

import static com.norconex.crawler.core.cmd.crawl.RunSummaryWriter.SYS_PROP_RUN_SUMMARY_FILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.ledger.CrawlerEntryLedger;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.core.session.CrawlerState;

import tools.jackson.databind.json.JsonMapper;

class RunSummaryWriterTest {

    @TempDir
    private Path tempDir;

    @AfterEach
    void clearProperty() {
        System.clearProperty(SYS_PROP_RUN_SUMMARY_FILE);
    }

    private CrawlerSession sessionProcessing(ProcessingOutcome... outcomes) {
        var ledger = mock(CrawlerEntryLedger.class);
        doAnswer(invocation -> {
            Consumer<CrawlerEntry> consumer = invocation.getArgument(0);
            for (ProcessingOutcome outcome : outcomes) {
                var entry = mock(CrawlerEntry.class);
                when(entry.getProcessingOutcome()).thenReturn(outcome);
                consumer.accept(entry);
            }
            return null;
        }).when(ledger).forEachProcessed(any());

        var ctx = mock(CrawlerContext.class);
        when(ctx.getId()).thenReturn("test-crawler");
        when(ctx.getCrawlEntryLedger()).thenReturn(ledger);

        var session = mock(CrawlerSession.class);
        when(session.getCrawlContext()).thenReturn(ctx);
        return session;
    }

    @Test
    void writesNothingWithoutTheSystemProperty() {
        var session = sessionProcessing(ProcessingOutcome.NEW);

        RunSummaryWriter.writeIfRequested(session, CrawlerState.COMPLETED);

        assertThat(tempDir).isEmptyDirectory();
    }

    @Test
    void writesCountsKeyedByProcessingOutcome() {
        var target = tempDir.resolve("summary.json");
        System.setProperty(SYS_PROP_RUN_SUMMARY_FILE, target.toString());

        var session = sessionProcessing(
                ProcessingOutcome.NEW,
                ProcessingOutcome.MODIFIED,
                ProcessingOutcome.MODIFIED,
                ProcessingOutcome.UNMODIFIED);

        RunSummaryWriter.writeIfRequested(session, CrawlerState.COMPLETED);

        assertThat(target).exists();
        var json = JsonMapper.builder().build().readTree(read(target));

        assertThat(json.get("crawlerId").asString()).isEqualTo("test-crawler");
        assertThat(json.get("state").asString()).isEqualTo("COMPLETED");

        // The crawler's own vocabulary, not a translation of it: whoever reads
        // this file gets the same words the ledger uses.
        var counts = json.get("counts");
        assertThat(counts.get("NEW").asLong()).isEqualTo(1);
        assertThat(counts.get("MODIFIED").asLong()).isEqualTo(2);
        assertThat(counts.get("UNMODIFIED").asLong()).isEqualTo(1);
        assertThat(counts.propertyNames()).hasSize(3);
    }

    @Test
    void createsMissingParentDirectories() {
        var target = tempDir.resolve("nested/deeper/summary.json");
        System.setProperty(SYS_PROP_RUN_SUMMARY_FILE, target.toString());

        RunSummaryWriter.writeIfRequested(
                sessionProcessing(ProcessingOutcome.NEW),
                CrawlerState.COMPLETED);

        assertThat(target).exists();
    }

    @Test
    void leavesNoTemporaryFileBehind() {
        var target = tempDir.resolve("summary.json");
        System.setProperty(SYS_PROP_RUN_SUMMARY_FILE, target.toString());

        RunSummaryWriter.writeIfRequested(
                sessionProcessing(ProcessingOutcome.NEW),
                CrawlerState.COMPLETED);

        // The file is renamed into place so a reader never sees it half
        // written; the staging file must not survive that.
        assertThat(tempDir.resolve("summary.json.tmp")).doesNotExist();
    }

    @Test
    void reportsAnEmptyCrawlAsEmptyCountsRatherThanNoFile() {
        var target = tempDir.resolve("summary.json");
        System.setProperty(SYS_PROP_RUN_SUMMARY_FILE, target.toString());

        RunSummaryWriter.writeIfRequested(
                sessionProcessing(), CrawlerState.COMPLETED);

        // "Nothing was processed" is an answer. A missing file would be
        // indistinguishable from the crawler having died before writing one.
        assertThat(target).exists();
        var json = JsonMapper.builder().build().readTree(read(target));
        assertThat(json.get("counts").isEmpty()).isTrue();
    }

    @Test
    void aFailureToReportDoesNotFailTheCrawl() {
        var target = tempDir.resolve("summary.json");
        System.setProperty(SYS_PROP_RUN_SUMMARY_FILE, target.toString());

        var ledger = mock(CrawlerEntryLedger.class);
        doThrow(new IllegalStateException("ledger is gone"))
                .when(ledger).forEachProcessed(any());
        var ctx = mock(CrawlerContext.class);
        when(ctx.getId()).thenReturn("test-crawler");
        when(ctx.getCrawlEntryLedger()).thenReturn(ledger);
        var session = mock(CrawlerSession.class);
        when(session.getCrawlContext()).thenReturn(ctx);

        // A report that could not be written is not a reason to fail a crawl
        // that otherwise succeeded.
        assertThatNoException().isThrownBy(() -> RunSummaryWriter
                .writeIfRequested(session, CrawlerState.COMPLETED));
    }

    @Test
    void recordsTheStateACrawlEndedIn() {
        for (var state : List.of(
                CrawlerState.COMPLETED,
                CrawlerState.STOPPED,
                CrawlerState.FAILED)) {
            var target = tempDir.resolve(state + ".json");
            System.setProperty(SYS_PROP_RUN_SUMMARY_FILE, target.toString());

            RunSummaryWriter.writeIfRequested(
                    sessionProcessing(ProcessingOutcome.NEW), state);

            var json = JsonMapper.builder().build().readTree(read(target));
            assertThat(json.get("state").asString()).isEqualTo(state.name());
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
