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
package com.norconex.crawler.core.cmd.crawl.pipeline.orphans;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.ledger.CrawlerEntryLedger;
import com.norconex.crawler.core.session.CrawlerSession;

/**
 * Tests for {@link RequeueOrphansForDeletionStep}.
 */
@Timeout(30)
class RequeueOrphansForDeletionStepTest {

    private CrawlerSession buildSession(CrawlerEntryLedger ledger) {
        var session = mock(CrawlerSession.class);
        var crawlContext = mock(CrawlerContext.class);
        when(session.getCrawlContext()).thenReturn(crawlContext);
        when(crawlContext.getCrawlEntryLedger()).thenReturn(ledger);
        return session;
    }

    @Test
    void noOrphans_doesNotQueueAnything() {
        var ledger = mock(CrawlerEntryLedger.class);
        when(ledger.getBaselineCount()).thenReturn(0L);

        new RequeueOrphansForDeletionStep("step-id")
                .execute(buildSession(ledger));

        verify(ledger, never()).queue(any());
    }

    @Test
    void crawlEndedEarly_doesNotDeleteAnything() {
        // References still queued mean the crawl never looked at them, so
        // their absence from this run proves nothing. This is what a crawl
        // that hit maxDocuments or maxCrawlDuration looks like, and without
        // the guard every reference it had not reached yet was deleted from
        // the target as an orphan.
        var ledger = mock(CrawlerEntryLedger.class);
        when(ledger.getBaselineCount()).thenReturn(5L);
        when(ledger.isQueuedEntryEmpty()).thenReturn(false);
        when(ledger.getQueuedEntryCount()).thenReturn(1_000L);

        new RequeueOrphansForDeletionStep("step-id")
                .execute(buildSession(ledger));

        verify(ledger, never()).queue(any());
        verify(ledger, never()).forEachBaseline(any());
    }

    @Test
    void completedCrawlWithOrphans_queuesAllForDeletion() {
        var ledger = mock(CrawlerEntryLedger.class);
        var entry1 = new CrawlerEntry("ref-a");
        var entry2 = new CrawlerEntry("ref-b");
        var queued = new ArrayList<CrawlerEntry>();

        when(ledger.getBaselineCount()).thenReturn(2L);
        // The crawl drained its queue, so absence is authoritative.
        when(ledger.isQueuedEntryEmpty()).thenReturn(true);
        doAnswer(inv -> {
            Consumer<CrawlerEntry> consumer = inv.getArgument(0);
            consumer.accept(entry1);
            consumer.accept(entry2);
            return null;
        }).when(ledger).forEachBaseline(any());
        doAnswer(inv -> {
            queued.add(inv.getArgument(0));
            return null;
        }).when(ledger).queue(any());

        new RequeueOrphansForDeletionStep("step-id")
                .execute(buildSession(ledger));

        verify(ledger, times(2)).queue(any());
        assertThat(queued).allMatch(CrawlerEntry::isDeleted);
        assertThat(queued).extracting(CrawlerEntry::getReference)
                .containsExactlyInAnyOrder("ref-a", "ref-b");
    }
}
