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
package com.norconex.crawler.core.cmd.crawl.pipeline.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.cluster.Cluster;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.ledger.CrawlerEntryLedger;
import com.norconex.crawler.core.session.CrawlerSession;

/**
 * Tests for {@link CrawlerActivityChecker}.
 */
@Timeout(30)
class CrawlerActivityCheckerTest {

    private CrawlerSession buildSession(boolean queueEmpty,
            boolean maxDocsReached) {
        var ledger = mock(CrawlerEntryLedger.class);
        when(ledger.getQueuedEntryCount()).thenReturn(queueEmpty ? 0L : 1L);
        when(ledger.isMaxDocsProcessedReached()).thenReturn(maxDocsReached);

        var config = mock(CrawlerConfig.class);
        when(config.getMaxCrawlDuration()).thenReturn(null);
        when(config.getIdleTimeout()).thenReturn(null);

        var crawlContext = mock(CrawlerContext.class);
        when(crawlContext.getCrawlEntryLedger()).thenReturn(ledger);
        when(crawlContext.getCrawlConfig()).thenReturn(config);

        var session = mock(CrawlerSession.class);
        when(session.getCrawlContext()).thenReturn(crawlContext);
        when(session.isStartRefsQueueingComplete()).thenReturn(true);
        return session;
    }

    @Test
    void constructor_withNullMaxDuration_doesNotThrow() {
        // null maxDuration → expireAt=0 (no expiry)
        var session = buildSession(false, false);
        var checker = new CrawlerActivityChecker(session, false);
        assertThat(checker).isNotNull();
    }

    @Test
    void isDeleting_reflectsConstructorFlag() {
        var session = buildSession(false, false);
        assertThat(new CrawlerActivityChecker(session, true).isDeleting())
                .isTrue();
        assertThat(new CrawlerActivityChecker(session, false).isDeleting())
                .isFalse();
    }

    @Test
    void isActive_whenQueueNotEmpty_returnsTrue() {
        var session = buildSession(false, false);
        var checker = new CrawlerActivityChecker(session, false);
        assertThat(checker.isActive()).isTrue();
    }

    @Test
    void isActive_whenQueueEmptyAndInitialized_returnsFalse() {
        // queue empty + queuing complete + no idleTimeout → not active
        var session = buildSession(true, false);
        var checker = new CrawlerActivityChecker(session, false);
        assertThat(checker.isActive()).isFalse();
    }

    @Test
    void canContinue_whenDeleting_returnsTrueEvenIfMaxDocsReached() {
        // deleting=true bypasses max-docs check
        var session = buildSession(false, true);
        var checker = new CrawlerActivityChecker(session, true);
        assertThat(checker.canContinue()).isTrue();
    }

    @Test
    void canContinue_whenMaxDocsReached_returnsFalse() {
        var session = buildSession(false, true);
        var checker = new CrawlerActivityChecker(session, false);
        // session.updateCrawlState is a no-op on mock
        assertThat(checker.canContinue()).isFalse();
    }

    @Test
    void doCanContinue_whenNothingReached_returnsTrue() {
        var session = buildSession(false, false);
        var checker = new CrawlerActivityChecker(session, false);
        assertThat(checker.doCanContinue()).isTrue();
    }

    @Test
    void canContinue_whenMaxDurationExpired_returnsFalse()
            throws InterruptedException {
        var ledger = mock(CrawlerEntryLedger.class);
        when(ledger.getQueuedEntryCount()).thenReturn(1L);
        when(ledger.isMaxDocsProcessedReached()).thenReturn(false);

        var config = mock(CrawlerConfig.class);
        when(config.getMaxCrawlDuration()).thenReturn(Duration.ofMillis(1));
        when(config.getIdleTimeout()).thenReturn(null);

        var crawlContext = mock(CrawlerContext.class);
        when(crawlContext.getCrawlEntryLedger()).thenReturn(ledger);
        when(crawlContext.getCrawlConfig()).thenReturn(config);

        var session = mock(CrawlerSession.class);
        when(session.getCrawlContext()).thenReturn(crawlContext);
        when(session.isStartRefsQueueingComplete()).thenReturn(true);

        var checker = new CrawlerActivityChecker(session, false);
        Thread.sleep(20); // ensure the 1ms duration has expired
        assertThat(checker.doCanContinue()).isFalse();
    }

    @Test
    void isActive_afterBecomingInactive_returnsFalseFromCache() {
        // queue empty + fully initialized → isActive() sets isPresumedActive=false
        var session = buildSession(true, false);
        var checker = new CrawlerActivityChecker(session, false);
        // First call: resolves and sets isPresumedActive = false
        assertThat(checker.isActive()).isFalse();
        // Second call: early-return from cached isPresumedActive == false
        assertThat(checker.isActive()).isFalse();
    }

    @Test
    void canContinue_afterBecomingFalse_cachedFalseReturnedFast() {
        // maxDocsReached=true → canContinue() returns false and caches result
        var session = buildSession(false, true);
        var checker = new CrawlerActivityChecker(session, false);
        assertThat(checker.canContinue()).isFalse(); // sets canContinue=false
        // Second call takes the fast-path: !canContinue.get() → return false
        assertThat(checker.canContinue()).isFalse();
    }

    @Test
    void isActive_withMaxDocsReached_triggersDoIsActiveCanContinueBranch() {
        // queue NOT empty (queueEmpty=false) but maxDocsReached=true
        // → isActive() → doIsActive() → !canContinue() path
        var session = buildSession(false, true);
        var checker = new CrawlerActivityChecker(session, false);
        assertThat(checker.isActive()).isFalse();
    }

    @Test
    void isActive_withShortIdleTimeout_queueStillEmpty_returnsFalse() {
        // queue initialized + empty + idleTimeout=50ms → wait loop runs ≈1s
        var ledger = mock(CrawlerEntryLedger.class);
        when(ledger.getQueuedEntryCount()).thenReturn(0L);
        when(ledger.isMaxDocsProcessedReached()).thenReturn(false);

        var config = mock(CrawlerConfig.class);
        when(config.getMaxCrawlDuration()).thenReturn(null);
        when(config.getIdleTimeout()).thenReturn(Duration.ofMillis(50));

        var crawlContext = mock(CrawlerContext.class);
        when(crawlContext.getCrawlEntryLedger()).thenReturn(ledger);
        when(crawlContext.getCrawlConfig()).thenReturn(config);

        var session = mock(CrawlerSession.class);
        when(session.getCrawlContext()).thenReturn(crawlContext);
        when(session.isStartRefsQueueingComplete()).thenReturn(true);

        var checker = new CrawlerActivityChecker(session, false);
        // Queue is empty + 50ms timeout elapses before poll loop finishes →
        // considered still empty → doIsActive returns false
        assertThat(checker.isActive()).isFalse();
    }

    @Test
    void isActive_withQueueEmptyAndNotYetInitialized_waitsForInit()
            throws InterruptedException {
        // Session: queue empty + first call to isStartRefsQueueingComplete
        // returns false, second returns true → tests the do-while wait loop
        var ledger = mock(CrawlerEntryLedger.class);
        when(ledger.getQueuedEntryCount()).thenReturn(0L);
        when(ledger.isMaxDocsProcessedReached()).thenReturn(false);

        var config = mock(CrawlerConfig.class);
        when(config.getMaxCrawlDuration()).thenReturn(null);
        when(config.getIdleTimeout()).thenReturn(null); // no idle timeout

        var crawlContext = mock(CrawlerContext.class);
        when(crawlContext.getCrawlEntryLedger()).thenReturn(ledger);
        when(crawlContext.getCrawlConfig()).thenReturn(config);

        var session = mock(CrawlerSession.class);
        when(session.getCrawlContext()).thenReturn(crawlContext);
        // First call: not ready; subsequent calls: ready
        when(session.isStartRefsQueueingComplete())
                .thenReturn(false)
                .thenReturn(true);

        var checker = new CrawlerActivityChecker(session, false);
        // do-while loop fires once (sleeps 1s), then sees initialized=true
        // → queue is empty+initialized → idle check returns true → inactive
        assertThat(checker.isActive()).isFalse();
    }

    // --- Documents still being processed when the queue is empty ---

    private CrawlerSession buildSessionWithProcessing(
            CrawlerEntryLedger ledger) {
        when(ledger.getQueuedEntryCount()).thenReturn(0L);
        when(ledger.isMaxDocsProcessedReached()).thenReturn(false);

        var config = mock(CrawlerConfig.class);
        when(config.getMaxCrawlDuration()).thenReturn(null);
        when(config.getIdleTimeout()).thenReturn(null);

        var crawlContext = mock(CrawlerContext.class);
        when(crawlContext.getCrawlEntryLedger()).thenReturn(ledger);
        when(crawlContext.getCrawlConfig()).thenReturn(config);

        var cluster = mock(Cluster.class);
        when(cluster.getWorkingNodeNames()).thenReturn(Set.of("node-a"));

        var session = mock(CrawlerSession.class);
        when(session.getCrawlContext()).thenReturn(crawlContext);
        when(session.getCluster()).thenReturn(cluster);
        when(session.isStartRefsQueueingComplete()).thenReturn(true);
        return session;
    }

    @Test
    void isActive_waitsForDocumentsInProcessBeforeFinishing() {
        var ledger = mock(CrawlerEntryLedger.class);
        // Still one being processed on the first check, done after.
        when(ledger.getProcessingCount()).thenReturn(1L, 0L);
        var session = buildSessionWithProcessing(ledger);

        var checker = new CrawlerActivityChecker(session, false);

        assertThat(checker.isActive()).isFalse();
        verify(ledger, atLeastOnce())
                .requeueStrandedEntries(Set.of("node-a"));
    }

    @Test
    void isActive_whenStrandedEntriesPutBack_returnsTrue() {
        var ledger = mock(CrawlerEntryLedger.class);
        when(ledger.getProcessingCount()).thenReturn(3L);
        when(ledger.requeueStrandedEntries(any())).thenReturn(3);
        var session = buildSessionWithProcessing(ledger);

        var checker = new CrawlerActivityChecker(session, false);

        assertThat(checker.isActive()).isTrue();
    }

    @Test
    void isActive_whenStopRequestedWhileWaiting_returnsFalse() {
        var ledger = mock(CrawlerEntryLedger.class);
        when(ledger.getProcessingCount()).thenReturn(1L);
        var session = buildSessionWithProcessing(ledger);
        when(session.isStopRequested()).thenReturn(true);

        var checker = new CrawlerActivityChecker(session, false);

        assertThat(checker.isActive()).isFalse();
        verify(ledger, never()).requeueStrandedEntries(any());
    }

    @Test
    void isActive_whenLedgerQueuedButQueueEmpty_putsThemBack() {
        // A node died after taking references off the queue but before
        // claiming them: the ledger still has them QUEUED.
        var session = buildSession(false, false);
        var ledger = session.getCrawlContext().getCrawlEntryLedger();
        when(ledger.isQueueEmpty()).thenReturn(true);

        var checker = new CrawlerActivityChecker(session, false);

        assertThat(checker.isActive()).isTrue();
        verify(ledger).requeueQueuedEntries();
    }

    @Test
    void isActive_whenQueueHasReferences_putsNothingBack() {
        var session = buildSession(false, false);
        var ledger = session.getCrawlContext().getCrawlEntryLedger();
        when(ledger.isQueueEmpty()).thenReturn(false);

        var checker = new CrawlerActivityChecker(session, false);

        assertThat(checker.isActive()).isTrue();
        verify(ledger, never()).requeueQueuedEntries();
    }
}
