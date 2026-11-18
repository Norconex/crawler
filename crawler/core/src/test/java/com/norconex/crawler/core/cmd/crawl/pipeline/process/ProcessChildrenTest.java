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
package com.norconex.crawler.core.cmd.crawl.pipeline.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import com.norconex.committer.core.service.CommitterService;
import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.cluster.Cluster;
import com.norconex.crawler.core.cluster.ClusterNode;
import com.norconex.crawler.core.cluster.support.InMemoryCacheManager;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.doc.CrawlerDocContext;
import com.norconex.crawler.core.doc.pipelines.CrawlerDocPipelines;
import com.norconex.crawler.core.doc.pipelines.committer.CommitterPipeline;
import com.norconex.crawler.core.doc.pipelines.importer.ImporterPipeline;
import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.ledger.CrawlerEntryLedger;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.core.ledger.ProcessingStatus;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.importer.response.ImporterResponse;
import com.norconex.importer.response.ImporterResponse.Status;
import com.norconex.importer.doc.Doc;

/**
 * What becomes of the documents split off from another one (the chunks of a
 * text, the entries of an archive) when their parent is dealt with in the next
 * crawl. They cannot be fetched by their own reference, so they follow their
 * parent: kept when it is not processed again, replaced when it is, deleted
 * when it goes. Run against a real ledger, with the previous crawl made the
 * way the crawler makes it: entries processed, then archived as the baseline.
 */
@Timeout(30)
class ProcessChildrenTest {

    private CrawlerSession session;
    private CrawlerEntryLedger ledger;
    private CommitterService committerService;
    private ImporterPipeline importerPipeline;

    @BeforeEach
    void setUp() {
        var cluster = mock(Cluster.class);
        var node = mock(ClusterNode.class);
        when(cluster.getCacheManager()).thenReturn(new InMemoryCacheManager());
        when(cluster.getLocalNode()).thenReturn(node);
        when(node.getNodeName()).thenReturn("unit-test-node");
        when(node.isCoordinator()).thenReturn(true);

        committerService = mock(CommitterService.class);
        importerPipeline = mock(ImporterPipeline.class);
        var docPipelines = CrawlerDocPipelines.builder()
                .importerPipeline(importerPipeline)
                .committerPipeline(mock(CommitterPipeline.class))
                .build();

        var crawlCtx = mock(CrawlerContext.class);
        session = mock(CrawlerSession.class);
        when(session.getCluster()).thenReturn(cluster);
        when(session.getCrawlContext()).thenReturn(crawlCtx);
        when(session.isIncremental()).thenReturn(true);
        when(crawlCtx.getCrawlConfig()).thenReturn(new CrawlerConfig());
        when(crawlCtx.getCommitterService()).thenReturn(committerService);
        when(crawlCtx.getDocPipelines()).thenReturn(docPipelines);
        when(crawlCtx.createCrawlEntry(any())).thenAnswer(
                inv -> new CrawlerEntry(inv.getArgument(0, String.class)));

        ledger = new CrawlerEntryLedger();
        when(crawlCtx.getCrawlEntryLedger()).thenReturn(ledger);
        ledger.init(session);
        ledger.ensureCurrentLedgerAliasExists();
    }

    //--- Helpers ------------------------------------------------------------

    private static CrawlerEntry entry(
            String ref, String parent, int childCount,
            ProcessingOutcome outcome) {
        var e = new CrawlerEntry(ref);
        e.setParentReference(parent);
        e.setChildCount(childCount);
        e.setProcessingOutcome(outcome);
        e.setProcessingStatus(ProcessingStatus.PROCESSED);
        return e;
    }

    /** The previous crawl: these entries, processed, then archived. */
    private void previousCrawl(CrawlerEntry... entries) {
        for (var e : entries) {
            ledger.updateEntry(e);
        }
        ledger.archiveCurrentLedger();
    }

    /** A text of three chunks, split and rejected, as the last crawl left it. */
    private void aSplitTextAndItsChunks() {
        previousCrawl(
                entry("doc", null, 3, ProcessingOutcome.REJECTED),
                entry("doc#1", "doc", 0, ProcessingOutcome.NEW),
                entry("doc#2", "doc", 0, ProcessingOutcome.NEW),
                entry("doc#3", "doc", 0, ProcessingOutcome.NEW));
    }

    private ProcessContext contextOf(
            String ref, ProcessingOutcome outcome) {
        var current = new CrawlerEntry(ref);
        current.setProcessingOutcome(outcome);
        var docCtx = CrawlerDocContext.builder()
                .doc(new Doc(ref))
                .currentCrawlEntry(current)
                .previousCrawlEntry(
                        ledger.getBaselineEntry(ref).orElse(null))
                .build();
        return new ProcessContext().crawlSession(session).docContext(docCtx);
    }

    private List<String> deletedReferences() {
        var docs = ArgumentCaptor.forClass(Doc.class);
        verify(committerService, org.mockito.Mockito.atLeast(0))
                .delete(docs.capture());
        return docs.getAllValues().stream().map(Doc::getReference).toList();
    }

    //--- An unmodified parent -------------------------------------------------

    @Test
    void anUnmodifiedParentKeepsItsChildren_whichAreNoLongerOrphans() {
        aSplitTextAndItsChunks();

        var ctx = contextOf("doc", ProcessingOutcome.UNMODIFIED);
        ProcessFinalize.execute(ctx);

        assertThat(ledger.getBaselineCount()).isZero();
        for (var chunk : List.of("doc#1", "doc#2", "doc#3")) {
            var kept = ledger.getEntry(chunk).orElseThrow();
            assertThat(kept.getProcessingOutcome())
                    .isEqualTo(ProcessingOutcome.UNMODIFIED);
            assertThat(kept.getParentReference()).isEqualTo("doc");
        }
        // So the crawl after this one still knows them.
        assertThat(ledger.getEntry("doc").orElseThrow().getChildCount())
                .isEqualTo(3);
        verify(committerService, never()).delete(any());
    }

    @Test
    void theChildrenOfAnUnmodifiedParentAreKeptAllTheWayDown() {
        previousCrawl(
                entry("zip", null, 1, ProcessingOutcome.NEW),
                entry("zip!inner.zip", "zip", 1, ProcessingOutcome.NEW),
                entry("zip!inner.zip!a.txt", "zip!inner.zip", 0,
                        ProcessingOutcome.NEW));

        ProcessFinalize.execute(contextOf("zip", ProcessingOutcome.UNMODIFIED));

        assertThat(ledger.getBaselineCount()).isZero();
        assertThat(ledger.getEntry("zip!inner.zip!a.txt")).isPresent();
        verify(committerService, never()).delete(any());
    }

    @Test
    void whateverOrderTheLedgerReturnsThemIn_aChildIsNeverDealtWithOnItsOwn() {
        // The children come first in the baseline: they must still wait for
        // the parent, and be kept with it.
        previousCrawl(
                entry("doc#1", "doc", 0, ProcessingOutcome.NEW),
                entry("doc#2", "doc", 0, ProcessingOutcome.NEW),
                entry("doc", null, 2, ProcessingOutcome.REJECTED));

        ProcessFinalize.execute(contextOf("doc", ProcessingOutcome.UNMODIFIED));

        assertThat(ledger.getBaselineCount()).isZero();
        assertThat(ledger.getEntry("doc#1")).isPresent();
        assertThat(ledger.getEntry("doc#2")).isPresent();
    }

    @Test
    void aParentWithNoRecordedChildrenIsLeftAsItWas_asInALedgerFromBefore() {
        // Written before entries knew their children: no count, no link.
        previousCrawl(
                entry("doc", null, 0, ProcessingOutcome.REJECTED),
                entry("doc#1", null, 0, ProcessingOutcome.NEW));

        ProcessFinalize.execute(contextOf("doc", ProcessingOutcome.UNMODIFIED));

        assertThat(ledger.getBaselineEntry("doc#1")).isPresent();
        assertThat(ledger.getEntry("doc#1")).isEmpty();
    }

    @Test
    void aParentWithAProblemThatMayPassKeepsItsChildrenToo() {
        aSplitTextAndItsChunks();

        ProcessFinalize.execute(contextOf("doc", ProcessingOutcome.ERROR));

        assertThat(ledger.getBaselineCount()).isZero();
        assertThat(ledger.getEntry("doc#3")).isPresent();
        verify(committerService, never()).delete(any());
    }

    //--- A parent that goes ---------------------------------------------------

    @Test
    void aDeletedParentTakesItsChildrenWithIt() {
        aSplitTextAndItsChunks();
        // The parent was rejected the time before: it was never sent, but its
        // chunks were.

        ProcessDelete.execute(contextOf("doc", ProcessingOutcome.DELETED));

        // The parent too, as before: asking for a deletion is not conditional.
        assertThat(deletedReferences())
                .containsExactlyInAnyOrder("doc", "doc#1", "doc#2", "doc#3");
        assertThat(ledger.getBaselineCount()).isZero();
        assertThat(ledger.getEntry("doc#2").orElseThrow()
                .getProcessingOutcome()).isEqualTo(ProcessingOutcome.DELETED);
    }

    @Test
    void aParentNowRejectedBeforeBeingReadTakesItsChildrenWithIt() {
        aSplitTextAndItsChunks();

        ProcessFinalize.execute(contextOf("doc", ProcessingOutcome.REJECTED));

        assertThat(deletedReferences())
                .containsExactlyInAnyOrder("doc#1", "doc#2", "doc#3");
    }

    @Test
    void whatWasNeverSentIsNotAskedToBeDeleted() {
        previousCrawl(
                entry("doc", null, 2, ProcessingOutcome.NEW),
                entry("doc#1", "doc", 0, ProcessingOutcome.NEW),
                // This one was rejected: nothing to delete in the target.
                entry("doc#2", "doc", 0, ProcessingOutcome.REJECTED));

        ProcessDelete.execute(contextOf("doc", ProcessingOutcome.DELETED));

        assertThat(deletedReferences()).containsExactly("doc#1", "doc");
        assertThat(ledger.getBaselineCount()).isZero();
    }

    //--- A parent that is processed again ---------------------------------------

    @Test
    void aParentProcessedAgainReplacesItsChildrenAndDeletesWhatItNoLongerMakes() {
        aSplitTextAndItsChunks();
        // It now makes two chunks: #1 and #2, with a new #4 instead of #3.
        var parent = new ImporterResponse("doc", Status.REJECTED);
        parent.setNestedResponses(List.of(
                childResponse("doc#1"), childResponse("doc#2"),
                childResponse("doc#4")));
        when(importerPipeline.apply(any())).thenReturn(parent);

        var ctx = contextOf("doc", ProcessingOutcome.MODIFIED);
        ProcessUpsert.execute(ctx);

        assertThat(deletedReferences()).containsExactly("doc#3");
        assertThat(ctx.docContext().getCurrentCrawlEntry().getChildCount())
                .isEqualTo(3);
        // The new and the replaced ones know their parent, and are not new.
        assertThat(
                ledger.getEntry("doc#1").orElseThrow().getProcessingOutcome())
                        .isEqualTo(ProcessingOutcome.MODIFIED);
        assertThat(
                ledger.getEntry("doc#4").orElseThrow().getProcessingOutcome())
                        .isEqualTo(ProcessingOutcome.NEW);
        assertThat(ledger.getEntry("doc#4").orElseThrow().getParentReference())
                .isEqualTo("doc");
        assertThat(ledger.getBaselineCount()).isZero();
    }

    @Test
    void aParentThatNoLongerMakesAnyChildDeletesThemAll() {
        aSplitTextAndItsChunks();
        var parent = new ImporterResponse("doc", Status.SUCCESS);
        when(importerPipeline.apply(any())).thenReturn(parent);

        var ctx = contextOf("doc", ProcessingOutcome.MODIFIED);
        ProcessUpsert.execute(ctx);

        assertThat(deletedReferences())
                .containsExactlyInAnyOrder("doc#1", "doc#2", "doc#3");
        assertThat(ctx.docContext().getCurrentCrawlEntry().getChildCount())
                .isZero();
    }

    @Test
    void aDocumentWithoutChildrenDoesNotAskTheLedgerForAny() {
        previousCrawl(entry("page", null, 0, ProcessingOutcome.NEW));
        var response = new ImporterResponse("page", Status.SUCCESS);
        when(importerPipeline.apply(any())).thenReturn(response);

        ProcessUpsert.execute(contextOf("page", ProcessingOutcome.MODIFIED));

        verify(committerService, never()).delete(any());
        assertThat(ledger.getBaselineChildren("page")).isEmpty();
    }

    //--- The ledger -------------------------------------------------------------

    @Test
    void theLedgerFindsTheChildrenStillInTheBaselineOfAParent() {
        aSplitTextAndItsChunks();

        assertThat(ledger.getBaselineChildren("doc"))
                .extracting(CrawlerEntry::getReference)
                .containsExactlyInAnyOrder("doc#1", "doc#2", "doc#3");
        assertThat(ledger.getBaselineChildren("someone-else")).isEmpty();

        // One is processed: no longer in the baseline, no longer returned.
        ledger.updateEntry(entry("doc#2", "doc", 0, ProcessingOutcome.NEW));
        assertThat(ledger.getBaselineChildren("doc"))
                .extracting(CrawlerEntry::getReference)
                .containsExactlyInAnyOrder("doc#1", "doc#3");
    }

    @Test
    void theChildrenOfOneCrawlAreNotTheChildrenOfTheNext() {
        aSplitTextAndItsChunks();
        assertThat(ledger.getBaselineChildren("doc")).hasSize(3);

        // This crawl makes only one, then it becomes the baseline of the next.
        ledger.updateEntry(entry("doc", null, 1, ProcessingOutcome.REJECTED));
        ledger.updateEntry(entry("doc#1", "doc", 0, ProcessingOutcome.NEW));
        ledger.archiveCurrentLedger();

        assertThat(ledger.getBaselineChildren("doc"))
                .extracting(CrawlerEntry::getReference)
                .containsExactly("doc#1");
    }

    //--- Helpers for the importer's responses -----------------------------------

    private static ImporterResponse childResponse(String ref) {
        var child = new ImporterResponse(ref, Status.SUCCESS);
        child.setDoc(new Doc(ref));
        return child;
    }

    @Test
    void childrenAreCommittedAsBefore() {
        // Guards the helper above: a child response goes to the committer.
        var parent = new ImporterResponse("doc", Status.REJECTED);
        parent.setNestedResponses(List.of(childResponse("doc#1")));
        when(importerPipeline.apply(any())).thenReturn(parent);

        ProcessUpsert.execute(contextOf("doc", ProcessingOutcome.NEW));

        verify(session.getCrawlContext().getDocPipelines()
                .getCommitterPipeline(), times(1)).accept(any());
    }
}
