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
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.committer.core.service.CommitterService;
import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.doc.CrawlerDocContext;
import com.norconex.crawler.core.doc.operations.spoil.SpoiledReferenceStrategizer;
import com.norconex.crawler.core.doc.operations.spoil.SpoiledReferenceStrategy;
import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.ledger.CrawlerEntryLedger;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.importer.doc.Doc;

/**
 * Tests for {@link ProcessFinalize}.
 */
@Timeout(30)
class ProcessFinalizeTest {

    @SuppressWarnings("unchecked")
    private ProcessContext buildCtx(
            ProcessingOutcome currentOutcome,
            ProcessingOutcome previousOutcome,
            SpoiledReferenceStrategizer strategizer) {

        var entry = new CrawlerEntry("http://example.com/test");
        entry.setProcessingOutcome(currentOutcome);

        var docCtxBuilder = CrawlerDocContext.builder()
                .doc(new Doc("http://example.com/test"))
                .currentCrawlEntry(entry);

        if (previousOutcome != null) {
            var prevEntry = new CrawlerEntry(
                    "http://example.com/test");
            prevEntry.setProcessingOutcome(previousOutcome);
            docCtxBuilder.previousCrawlEntry(prevEntry);
        }
        var docCtx = docCtxBuilder.build();

        var config = mock(CrawlerConfig.class);
        when(config.getSpoiledReferenceStrategizer())
                .thenReturn(strategizer);

        var ledger = mock(CrawlerEntryLedger.class);
        var committerService = mock(CommitterService.class);
        var crawlCtx = mock(CrawlerContext.class);
        when(crawlCtx.getCrawlEntryLedger()).thenReturn(ledger);
        when(crawlCtx.getCrawlConfig()).thenReturn(config);
        when(crawlCtx.getCommitterService())
                .thenReturn(committerService);

        var session = mock(CrawlerSession.class);
        when(session.getCrawlContext()).thenReturn(crawlCtx);

        return new ProcessContext()
                .crawlSession(session)
                .docContext(docCtx);
    }

    // -----------------------------------------------------------------
    // Early-return paths
    // -----------------------------------------------------------------

    @Test
    void execute_alreadyFinalized_returnsImmediately() {
        var ctx = buildCtx(ProcessingOutcome.NEW, null, null);
        ctx.finalized(true);
        assertThatNoException()
                .isThrownBy(() -> ProcessFinalize.execute(ctx));
        // finalized remains true, no state changes
        assertThat(ctx.finalized()).isTrue();
    }

    @Test
    void execute_nullDocContext_returnsImmediately() {
        var session = mock(CrawlerSession.class);
        var ctx = new ProcessContext().crawlSession(session);
        assertThatNoException()
                .isThrownBy(() -> ProcessFinalize.execute(ctx));
        assertThat(ctx.finalized()).isFalse();
    }

    // -----------------------------------------------------------------
    // Null processing outcome → sets BAD_STATUS
    // -----------------------------------------------------------------

    @Test
    void execute_nullProcessingOutcome_setsBadStatus() {
        var ctx = buildCtx(null, null, null); // no outcome set
        ProcessFinalize.execute(ctx);
        assertThat(ctx.docContext().getCurrentCrawlEntry()
                .getProcessingOutcome())
                        .isEqualTo(ProcessingOutcome.BAD_STATUS);
    }

    // -----------------------------------------------------------------
    // Good state → marks finalized + processed, no delete
    // -----------------------------------------------------------------

    @Test
    void execute_goodOutcome_setsFinalizedAndProcessed() {
        var ctx = buildCtx(ProcessingOutcome.NEW, null, null);
        assertThatNoException()
                .isThrownBy(() -> ProcessFinalize.execute(ctx));
        assertThat(ctx.finalized()).isTrue();
        // outcome unchanged (good state)
        assertThat(ctx.docContext().getCurrentCrawlEntry()
                .getProcessingOutcome()).isEqualTo(
                        ProcessingOutcome.NEW);
    }

    // -----------------------------------------------------------------
    // Bad state, no previous entry → no delete even with DELETE strategy
    // -----------------------------------------------------------------

    @Test
    void execute_badOutcome_noPreviousEntry_noDeleteCalled() {
        // Default fallback = DELETE, but previousEntry is null → no delete
        var ctx = buildCtx(ProcessingOutcome.ERROR, null, null);
        assertThatNoException()
                .isThrownBy(() -> ProcessFinalize.execute(ctx));
        // outcome is still ERROR (no DELETED override)
        assertThat(ctx.docContext().getCurrentCrawlEntry()
                .getProcessingOutcome()).isEqualTo(
                        ProcessingOutcome.ERROR);
    }

    // -----------------------------------------------------------------
    // dealWithBadState: IGNORE strategy
    // -----------------------------------------------------------------

    @Test
    void execute_badOutcome_ignoreStrategy_doesNothing() {
        var strategizer = mock(SpoiledReferenceStrategizer.class);
        when(strategizer.resolveSpoiledReferenceStrategy(any(), any()))
                .thenReturn(SpoiledReferenceStrategy.IGNORE);

        var ctx = buildCtx(ProcessingOutcome.ERROR,
                ProcessingOutcome.ERROR,
                strategizer);
        assertThatNoException()
                .isThrownBy(() -> ProcessFinalize.execute(ctx));
        // No delete → outcome stays ERROR
        assertThat(ctx.docContext().getCurrentCrawlEntry()
                .getProcessingOutcome()).isEqualTo(
                        ProcessingOutcome.ERROR);
    }

    // -----------------------------------------------------------------
    // dealWithBadState: Previous entry with NULL outcome → legacy warning
    // -----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    @Test
    void execute_badOutcome_previousWithNullOutcome_legacyWarningPath() {
        // Build manually: previous entry with null outcome (legacy case)
        var entry = new CrawlerEntry("http://example.com/legacy");
        entry.setProcessingOutcome(ProcessingOutcome.ERROR);

        var prevEntry = new CrawlerEntry("http://example.com/legacy");
        // prevEntry intentionally has null outcome

        var docCtx = CrawlerDocContext.builder()
                .doc(new Doc("http://example.com/legacy"))
                .currentCrawlEntry(entry)
                .previousCrawlEntry(prevEntry)
                .build();

        var config = mock(CrawlerConfig.class);
        var ledger = mock(CrawlerEntryLedger.class);
        var crawlCtx = mock(CrawlerContext.class);
        when(crawlCtx.getCrawlEntryLedger()).thenReturn(ledger);
        when(crawlCtx.getCrawlConfig()).thenReturn(config);
        when(crawlCtx.getCommitterService())
                .thenReturn(mock(CommitterService.class));

        var session = mock(CrawlerSession.class);
        when(session.getCrawlContext()).thenReturn(crawlCtx);

        var ctx = new ProcessContext()
                .crawlSession(session)
                .docContext(docCtx);

        // Should trigger legacy warning and return from dealWithBadState
        assertThatNoException()
                .isThrownBy(() -> ProcessFinalize.execute(ctx));
        // Outcome of prevEntry should now be ERROR (set by legacy warning)
        assertThat(prevEntry.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.ERROR);
    }

    // -----------------------------------------------------------------
    // dealWithBadState: DELETE strategy with prevEntry in bad state → calls delete
    // -----------------------------------------------------------------

    @Test
    void execute_badOutcome_deleteStrategy_withBadPrevious_callsDelete() {
        // Default fallback DELETE, prev has ERROR (bad state, not deleted)
        var ctx = buildCtx(ProcessingOutcome.ERROR,
                ProcessingOutcome.ERROR,
                null);
        assertThatNoException()
                .isThrownBy(() -> ProcessFinalize.execute(ctx));
        // ProcessDelete.execute() should have set outcome to DELETED
        assertThat(ctx.docContext().getCurrentCrawlEntry()
                .getProcessingOutcome()).isEqualTo(
                        ProcessingOutcome.DELETED);
    }

    // -----------------------------------------------------------------
    // dealWithBadState: GRACE_ONCE with good previous → grace (no delete)
    // -----------------------------------------------------------------

    @Test
    void execute_badOutcome_graceOnceStrategy_previousGoodState_doesNotDelete() {
        var strategizer = mock(SpoiledReferenceStrategizer.class);
        when(strategizer.resolveSpoiledReferenceStrategy(any(), any()))
                .thenReturn(SpoiledReferenceStrategy.GRACE_ONCE);

        // Previous had good state → grace once (DEBUG log, no delete)
        var ctx = buildCtx(ProcessingOutcome.ERROR,
                ProcessingOutcome.NEW,
                strategizer);
        assertThatNoException()
                .isThrownBy(() -> ProcessFinalize.execute(ctx));
        assertThat(ctx.docContext().getCurrentCrawlEntry()
                .getProcessingOutcome()).isEqualTo(
                        ProcessingOutcome.ERROR);
    }

    // -----------------------------------------------------------------
    // dealWithBadState: GRACE_ONCE with bad previous → deletes
    // -----------------------------------------------------------------

    @Test
    void execute_badOutcome_graceOnceStrategy_previousBadState_callsDelete() {
        var strategizer = mock(SpoiledReferenceStrategizer.class);
        when(strategizer.resolveSpoiledReferenceStrategy(any(), any()))
                .thenReturn(SpoiledReferenceStrategy.GRACE_ONCE);

        // Previous had bad state → GRACE_ONCE deletes on second bad
        var ctx = buildCtx(ProcessingOutcome.ERROR,
                ProcessingOutcome.ERROR,
                strategizer);
        assertThatNoException()
                .isThrownBy(() -> ProcessFinalize.execute(ctx));
        assertThat(ctx.docContext().getCurrentCrawlEntry()
                .getProcessingOutcome()).isEqualTo(
                        ProcessingOutcome.DELETED);
    }

    // -----------------------------------------------------------------
    // dealWithBadState: DELETED state is not re-deleted
    // -----------------------------------------------------------------

    @Test
    void execute_alreadyDeletedOutcome_doesNotTriggerDelete() {
        var ctx = buildCtx(ProcessingOutcome.DELETED, null, null);
        assertThatNoException()
                .isThrownBy(() -> ProcessFinalize.execute(ctx));
        // DELETED outcome → the bad-state check is gated by isOneOf(DELETED)
        assertThat(ctx.docContext().getCurrentCrawlEntry()
                .getProcessingOutcome()).isEqualTo(
                        ProcessingOutcome.DELETED);
    }

    // -----------------------------------------------------------------
    // Nothing was ever committed → nothing to delete
    // -----------------------------------------------------------------

    @Test
    void execute_previouslyRejected_doesNotDeleteAgain() {
        // A reference rejected last run was never committed under that
        // reference, so a delete would match nothing in the target. This is
        // the file system folder case: a folder is not a document, halts the
        // importer pipeline, and is labelled rejected -- and used to be
        // deleted from the customer's repository on every recrawl.
        var ctx = buildCtx(ProcessingOutcome.REJECTED,
                ProcessingOutcome.REJECTED,
                null);

        assertThatNoException()
                .isThrownBy(() -> ProcessFinalize.execute(ctx));

        assertThat(ctx.docContext().getCurrentCrawlEntry()
                .getProcessingOutcome())
                        .as("a rejected reference must not be deleted twice")
                        .isEqualTo(ProcessingOutcome.REJECTED);
    }

    @Test
    void execute_previouslyRejected_doesNotDeleteUnderGraceOnce() {
        var strategizer = mock(SpoiledReferenceStrategizer.class);
        when(strategizer.resolveSpoiledReferenceStrategy(any(), any()))
                .thenReturn(SpoiledReferenceStrategy.GRACE_ONCE);

        // GRACE_ONCE deletes on a second bad run, but "bad twice" still does
        // not mean the target holds anything to delete.
        var ctx = buildCtx(ProcessingOutcome.ERROR,
                ProcessingOutcome.REJECTED,
                strategizer);

        assertThatNoException()
                .isThrownBy(() -> ProcessFinalize.execute(ctx));

        assertThat(ctx.docContext().getCurrentCrawlEntry()
                .getProcessingOutcome()).isEqualTo(
                        ProcessingOutcome.ERROR);
    }

    @Test
    void execute_nonDocument_isNeitherGoodNorBad() {
        // A folder that yielded its children was processed exactly as
        // intended. It is not a good state (it is not a document at all) but
        // it must not go anywhere near spoiled-reference handling.
        var ctx = buildCtx(ProcessingOutcome.NON_DOCUMENT,
                ProcessingOutcome.NON_DOCUMENT,
                null);

        assertThatNoException()
                .isThrownBy(() -> ProcessFinalize.execute(ctx));

        assertThat(ctx.docContext().getCurrentCrawlEntry()
                .getProcessingOutcome())
                        .as("a non-document must never be deleted")
                        .isEqualTo(ProcessingOutcome.NON_DOCUMENT);
    }

    @Test
    void execute_previouslyNonDocument_doesNotDelete() {
        // Whatever it becomes now, nothing was ever committed under it.
        var ctx = buildCtx(ProcessingOutcome.ERROR,
                ProcessingOutcome.NON_DOCUMENT,
                null);

        assertThatNoException()
                .isThrownBy(() -> ProcessFinalize.execute(ctx));

        assertThat(ctx.docContext().getCurrentCrawlEntry()
                .getProcessingOutcome()).isEqualTo(
                        ProcessingOutcome.ERROR);
    }

    @Test
    void execute_committedThenRejected_stillDeletes() {
        // The case that must keep working: a document that WAS committed and
        // is now rejected (a filter changed, say) has to be removed from the
        // target. Only the repeat is suppressed, never the first delete.
        var ctx = buildCtx(ProcessingOutcome.REJECTED,
                ProcessingOutcome.NEW,
                null);

        assertThatNoException()
                .isThrownBy(() -> ProcessFinalize.execute(ctx));

        assertThat(ctx.docContext().getCurrentCrawlEntry()
                .getProcessingOutcome())
                        .as("a previously committed document must still be "
                                + "deleted when it turns rejected")
                        .isEqualTo(ProcessingOutcome.DELETED);
    }
}
