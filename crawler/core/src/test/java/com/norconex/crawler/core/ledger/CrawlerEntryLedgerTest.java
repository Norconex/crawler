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
package com.norconex.crawler.core.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;

import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.cluster.CacheMap;
import com.norconex.crawler.core.cluster.CacheNames;
import com.norconex.crawler.core.cluster.CacheQueue;
import com.norconex.crawler.core.cluster.ClusterException;
import com.norconex.crawler.core.cluster.Cluster;
import com.norconex.crawler.core.cluster.ClusterNode;
import com.norconex.crawler.core.cluster.support.InMemoryCacheManager;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.session.CrawlerSession;

/**
 * Unit tests for {@link CrawlerEntryLedger}.
 *
 * <p>Uses in-memory caches and Mockito stubs — no Hazelcast required.
 * Covers the full lifecycle: queue → process → processed → archive → resume.
 */
@ExtendWith(MockitoExtension.class)
@Timeout(30)
@SuppressWarnings("null")
class CrawlerEntryLedgerTest {

    @Mock(strictness = Strictness.LENIENT)
    CrawlerSession session;
    @Mock(strictness = Strictness.LENIENT)
    Cluster cluster;
    @Mock(strictness = Strictness.LENIENT)
    ClusterNode localNode;
    @Mock(strictness = Strictness.LENIENT)
    CrawlerContext crawlContext;

    private InMemoryCacheManager cacheManager;
    private CrawlerEntryLedger ledger;

    @BeforeEach
    void setUp() {
        cacheManager = new InMemoryCacheManager();
        // Wire in real CrawlerConfig so maxDocuments defaults to -1 (unlimited).
        var crawlConfig = new CrawlerConfig();

        lenient().when(session.getCluster()).thenReturn(cluster);
        lenient().when(cluster.getCacheManager()).thenReturn(cacheManager);
        lenient().when(cluster.getLocalNode()).thenReturn(localNode);
        lenient().when(localNode.getNodeName()).thenReturn("unit-test-node");
        lenient().when(localNode.isCoordinator()).thenReturn(true);
        lenient().when(session.getCrawlContext()).thenReturn(crawlContext);
        lenient().when(crawlContext.getCrawlConfig()).thenReturn(crawlConfig);
        lenient().when(session.isResumed()).thenReturn(false);

        ledger = new CrawlerEntryLedger();
        ledger.init(session);
        // archiveCurrentLedger() requires the alias key to already exist in
        // session cache (coordinator responsibility before any rotation).
        ledger.ensureCurrentLedgerAliasExists();
    }

    // -----------------------------------------------------------------
    // Queuing
    // -----------------------------------------------------------------

    @Test
    void testQueue_addsEntryToLedgerAndQueue() {
        ledger.queue(entry("ref-1"));

        assertThat(ledger.exists("ref-1")).isTrue();
        assertThat(ledger.getProcessingStatus("ref-1"))
                .isEqualTo(ProcessingStatus.QUEUED);
        assertThat(ledger.getQueueCount()).isEqualTo(1);
    }

    @Test
    void testQueue_duplicateIsIgnored() {
        ledger.queue(entry("ref-1"));
        ledger.queue(entry("ref-1")); // second call must be a no-op

        assertThat(ledger.getQueueCount()).isEqualTo(1);
        assertThat(ledger.countByStatus(ProcessingStatus.QUEUED))
                .isEqualTo(1);
    }

    @Test
    void testQueue_multipleEntries_countsAreCorrect() {
        for (var i = 0; i < 5; i++) {
            ledger.queue(entry("ref-" + i));
        }

        assertThat(ledger.getQueueCount()).isEqualTo(5);
        assertThat(ledger.countByStatus(ProcessingStatus.QUEUED))
                .isEqualTo(5);
        assertThat(ledger.isQueueEmpty()).isFalse();
    }

    // -----------------------------------------------------------------
    // Batch dequeuing → PROCESSING state
    // -----------------------------------------------------------------

    @Test
    void testNextQueuedBatch_promotesEntryToProcessing() {
        ledger.queue(entry("ref-1"));

        var batch = ledger.nextQueuedBatch(10);

        assertThat(batch).hasSize(1);
        assertThat(batch.get(0).getReference()).isEqualTo("ref-1");
        assertThat(ledger.getProcessingStatus("ref-1"))
                .isEqualTo(ProcessingStatus.PROCESSING);
        assertThat(ledger.getQueueCount()).isZero();
        assertThat(ledger.getProcessingCount()).isEqualTo(1);
    }

    @Test
    void testNextQueuedBatch_unclaimedButStillQueuedGoesBackInQueue() {
        // A cache that fails to claim an entry still QUEUED (it cannot
        // happen with the bundled caches, whose updates are atomic): the
        // reference, already taken off the queue, must not be lost.
        var failClaims = new AtomicBoolean(true);
        var failingLedger = ledgerWithStubbedMaps(spied -> doAnswer(
                inv -> failClaims.get() ? Map.of() : inv.callRealMethod())
                        .when(spied).updateAll(any(), any()));
        failingLedger.queue(entry("ref-1"));

        assertThat(failingLedger.nextQueuedBatch(10)).isEmpty();
        assertThat(failingLedger.getProcessingStatus("ref-1"))
                .isEqualTo(ProcessingStatus.QUEUED);

        failClaims.set(false);
        assertThat(failingLedger.nextQueuedBatch(10))
                .extracting(CrawlerEntry::getReference)
                .containsExactly("ref-1");
    }

    @Test
    void testNextQueuedBatch_failedBatchClaimFallsBackToOneAtATime() {
        // The batch claim throws (a cache failure): each reference is then
        // claimed on its own, and one whose claim fails too goes back in
        // the queue instead of being lost.
        var failRef2 = new AtomicBoolean(true);
        var failingLedger = ledgerWithStubbedMaps(spied -> {
            doThrow(new IllegalStateException("cluster hiccup"))
                    .when(spied).updateAll(any(), any());
            doAnswer(inv -> {
                if (failRef2.get() && "ref-2".equals(inv.getArgument(0))) {
                    throw new IllegalStateException("still failing");
                }
                return inv.callRealMethod();
            }).when(spied).update(any(), any());
        });
        failingLedger.queue(entry("ref-1"));
        failingLedger.queue(entry("ref-2"));

        assertThat(failingLedger.nextQueuedBatch(10))
                .extracting(CrawlerEntry::getReference)
                .containsExactly("ref-1");
        assertThat(failingLedger.getProcessingStatus("ref-2"))
                .isEqualTo(ProcessingStatus.QUEUED);

        failRef2.set(false);
        assertThat(failingLedger.nextQueuedBatch(10))
                .extracting(CrawlerEntry::getReference)
                .containsExactly("ref-2");
    }

    // A ledger whose ledger maps are spies, stubbed by the given stubber.
    @SuppressWarnings("unchecked")
    private CrawlerEntryLedger ledgerWithStubbedMaps(
            Consumer<CacheMap<CrawlerEntry>> stubber) {
        var spies = new HashMap<String, CacheMap<?>>();
        var manager = new InMemoryCacheManager() {
            @Override
            public <T> CacheMap<T> getCacheMap(String name, Class<T> type) {
                var map = super.getCacheMap(name, type);
                if (!name.startsWith("ledger_")) {
                    return map;
                }
                return (CacheMap<T>) spies.computeIfAbsent(name, n -> {
                    var spied = spy(map);
                    stubber.accept((CacheMap<CrawlerEntry>) spied);
                    return spied;
                });
            }
        };
        lenient().when(cluster.getCacheManager()).thenReturn(manager);
        var stubbedLedger = new CrawlerEntryLedger();
        stubbedLedger.init(session);
        stubbedLedger.ensureCurrentLedgerAliasExists();
        return stubbedLedger;
    }

    @Test
    void testNextQueuedBatch_skipsDuplicateReferenceAlreadyClaimed() {
        ledger.queue(entry("ref-1"));
        cacheManager.getCacheQueue(CacheNames.REFERENCE_QUEUE, String.class)
                .add("ref-1");

        assertThat(ledger.nextQueuedBatch(10))
                .extracting(CrawlerEntry::getReference)
                .containsExactly("ref-1");
        assertThat(ledger.getQueueCount()).isZero();
        assertThat(ledger.getProcessingCount()).isEqualTo(1);
    }

    @Test
    void testNextQueuedBatch_respectsBatchSizeLimit() {
        for (var i = 0; i < 10; i++) {
            ledger.queue(entry("ref-" + i));
        }

        var batch = ledger.nextQueuedBatch(3);

        assertThat(batch).hasSize(3);
        // Remaining references stay in the queue.
        assertThat(ledger.getQueueCount()).isEqualTo(7);
    }

    // -----------------------------------------------------------------
    // Requeue on resume
    // -----------------------------------------------------------------

    @Test
    void testRequeuProcessingEntries_movesBackToQueued() {
        ledger.queue(entry("ref-a"));
        ledger.nextQueuedBatch(1); // → PROCESSING

        var requeued = ledger.requeueProcessingEntries();

        assertThat(requeued).isEqualTo(1);
        assertThat(ledger.getProcessingStatus("ref-a"))
                .isEqualTo(ProcessingStatus.QUEUED);
        assertThat(ledger.getQueueCount()).isEqualTo(1);
        assertThat(ledger.getProcessingCount()).isZero();
    }

    @Test
    void testRequeuQueuedEntries_addsQueuedRefsBackToQueue() {
        ledger.queue(entry("ref-b"));
        // Simulate losing the queue (queue cleared externally); ledger still
        // has the QUEUED entries.
        cacheManager.getCacheQueue("queue-refs", String.class).clear();
        assertThat(ledger.getQueueCount()).isEqualTo(1);
        assertThat(ledger.isQueueEmpty()).isTrue();

        var requeued = ledger.requeueQueuedEntries();

        assertThat(requeued).isEqualTo(1);
        assertThat(ledger.getQueueCount()).isEqualTo(1);
        assertThat(ledger.isQueueEmpty()).isFalse();
    }

    // -----------------------------------------------------------------
    // Entry lifecycle — update and remove
    // -----------------------------------------------------------------

    @Test
    void testUpdateEntry_changesStatus() {
        ledger.queue(entry("ref-1"));
        var entry = ledger.nextQueuedBatch(1).get(0);
        entry.setProcessingStatus(ProcessingStatus.PROCESSED);
        entry.setProcessingOutcome(ProcessingOutcome.NEW);

        ledger.updateEntry(entry);

        assertThat(ledger.getProcessingStatus("ref-1"))
                .isEqualTo(ProcessingStatus.PROCESSED);
        assertThat(ledger.getProcessedCount()).isEqualTo(1);
    }

    @Test
    void testUpdateEntry_processedEntryRemovesMatchingBaselineEntry() {
        ledger.queue(entry("ref-1"));
        ledger.queue(entry("ref-2"));
        ledger.archiveCurrentLedger();

        ledger.queue(entry("ref-1"));
        var entry = ledger.nextQueuedBatch(1).get(0);
        entry.setProcessingStatus(ProcessingStatus.PROCESSED);
        entry.setProcessingOutcome(ProcessingOutcome.UNMODIFIED);

        ledger.updateEntry(entry);

        assertThat(ledger.getBaselineCount()).isEqualTo(1);
        assertThat(ledger.getBaselineEntry("ref-1")).isEmpty();
        assertThat(ledger.getBaselineEntry("ref-2")).isPresent();
    }

    @Test
    void testRequeueEntry_requeuesExistingProcessedEntry() {
        ledger.queue(entry("ref-1"));
        var entry = ledger.nextQueuedBatch(1).get(0);
        entry.setProcessingStatus(ProcessingStatus.PROCESSED);
        entry.setProcessingOutcome(ProcessingOutcome.REJECTED);
        ledger.updateEntry(entry);

        var requeued = ledger.requeueEntry("ref-1");

        assertThat(requeued).isTrue();
        assertThat(ledger.getProcessingStatus("ref-1"))
                .isEqualTo(ProcessingStatus.QUEUED);
        assertThat(ledger.getQueueCount()).isEqualTo(1);
    }

    @Test
    void testRequeueEntry_replacesTrackedEntryState() {
        ledger.queue(entry("ref-1"));
        var existing = ledger.nextQueuedBatch(1).get(0);
        existing.setProcessingStatus(ProcessingStatus.PROCESSED);
        existing.setProcessingOutcome(ProcessingOutcome.REJECTED);
        ledger.updateEntry(existing);

        var replacement = entry("ref-1");
        replacement.setProcessingOutcome(ProcessingOutcome.NEW);
        replacement.setDepth(5);

        var requeued = ledger.requeueEntry(replacement);

        assertThat(requeued).isTrue();
        assertThat(ledger.getQueueCount()).isEqualTo(1);
        assertThat(ledger.getEntry("ref-1")).get()
                .extracting(CrawlerEntry::getProcessingStatus,
                        CrawlerEntry::getProcessingOutcome,
                        CrawlerEntry::getDepth)
                .containsExactly(
                        ProcessingStatus.QUEUED,
                        ProcessingOutcome.NEW,
                        5);
    }

    @Test
    void testRemoveEntry_deletesFromLedger() {
        ledger.queue(entry("ref-1"));

        ledger.removeEntry("ref-1");

        assertThat(ledger.exists("ref-1")).isFalse();
    }

    // -----------------------------------------------------------------
    // Max-documents limit
    // -----------------------------------------------------------------

    @Test
    void testIsMaxDocsProcessedReached_notReachedWhenUnlimited() {
        for (var i = 0; i < 100; i++) {
            ledger.queue(entry("ref-" + i));
        }
        assertThat(ledger.isMaxDocsProcessedReached()).isFalse();
    }

    @Test
    void testIsMaxDocsProcessedReached_trueAtLimit() {
        // Reinitialise with a 2-document limit.
        var cappedConfig = new CrawlerConfig();
        cappedConfig.setMaxDocuments(2);
        lenient().when(crawlContext.getCrawlConfig()).thenReturn(cappedConfig);
        ledger = new CrawlerEntryLedger();
        ledger.init(session);

        // Queue 3, process 2.
        for (var i = 0; i < 3; i++) {
            ledger.queue(entry("ref-" + i));
        }
        var toProcess = ledger.nextQueuedBatch(2);
        for (var e : toProcess) {
            e.setProcessingStatus(ProcessingStatus.PROCESSED);
            ledger.updateEntry(e);
        }

        assertThat(ledger.isMaxDocsProcessedReached()).isTrue();
    }

    // -----------------------------------------------------------------
    // Ledger rotation (archive)
    // -----------------------------------------------------------------

    @Test
    void testArchiveCurrentLedger_currentBecomesBaselineAndNewCurrentIsEmpty() {
        ledger.queue(entry("ref-1"));
        ledger.queue(entry("ref-2"));

        ledger.archiveCurrentLedger();

        // After rotation the current ledger is empty.
        assertThat(ledger.countByStatus(ProcessingStatus.QUEUED)).isZero();
        assertThat(ledger.getQueueCount()).isZero();
        assertThat(ledger.isQueueEmpty()).isFalse(); // physical queue not cleared

        // The old entries are now in the baseline.
        assertThat(ledger.getBaselineCount()).isEqualTo(2);
        assertThat(ledger.getBaselineEntry("ref-1")).isPresent();
        assertThat(ledger.getBaselineEntry("ref-2")).isPresent();
    }

    @Test
    void testArchiveCurrentLedger_doubleRotationClearsOldBaseline() {
        ledger.queue(entry("ref-1"));
        ledger.archiveCurrentLedger(); // ledger_b is now current
        ledger.queue(entry("ref-2")); // goes to ledger_b
        ledger.archiveCurrentLedger(); // rotates back to ledger_a; old baseline cleared

        // Only ref-2 should be in the new baseline.
        assertThat(ledger.getBaselineCount()).isEqualTo(1);
        assertThat(ledger.getBaselineEntry("ref-2")).isPresent();
        assertThat(ledger.getBaselineEntry("ref-1")).isEmpty();
    }

    // -----------------------------------------------------------------
    // Clear queue
    // -----------------------------------------------------------------

    @Test
    void testClearQueue_removesQueuedEntriesAndQueue() {
        ledger.queue(entry("ref-1"));
        ledger.queue(entry("ref-2"));

        ledger.clearQueue();

        assertThat(ledger.getQueueCount()).isZero();
        assertThat(ledger.isQueueEmpty()).isTrue();
        assertThat(ledger.countByStatus(ProcessingStatus.QUEUED)).isZero();
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    // -----------------------------------------------------------------
    // Entries stranded with a node no longer working on the crawl
    // -----------------------------------------------------------------

    @Test
    void testNextQueuedBatch_recordsClaimingNode() {
        ledger.queue(entry("ref-1"));

        var claimed = ledger.nextQueuedBatch(10).get(0);

        assertThat(claimed.getProcessingNode()).isEqualTo("unit-test-node");
        assertThat(claimed.getProcessingAt()).isNotNull();
        assertThat(ledger.getEntry("ref-1").orElseThrow()
                .getProcessingNode()).isEqualTo("unit-test-node");
    }

    @Test
    void testRequeueStrandedEntries_putsBackOnlyThoseOfGoneNodes() {
        ledger.queue(entry("ref-a"));
        ledger.queue(entry("ref-b"));
        claimAs("node-a", 1);
        claimAs("node-b", 1);

        var requeued = ledger.requeueStrandedEntries(Set.of("node-b"));

        assertThat(requeued).isEqualTo(1);
        var back = ledger.getEntry("ref-a").orElseThrow();
        assertThat(back.getProcessingStatus())
                .isEqualTo(ProcessingStatus.QUEUED);
        assertThat(back.getProcessingNode()).isNull();
        assertThat(ledger.getProcessingStatus("ref-b"))
                .isEqualTo(ProcessingStatus.PROCESSING);
        assertThat(ledger.getQueueCount()).isEqualTo(1);

        // A working node takes it again.
        var retaken = claimAs("node-b", 10);
        assertThat(retaken).extracting(CrawlerEntry::getReference)
                .containsExactly("ref-a");
    }

    @Test
    void testRequeueStrandedEntries_putsBackOnce() {
        ledger.queue(entry("ref-a"));
        claimAs("node-a", 1);

        assertThat(ledger.requeueStrandedEntries(Set.of("node-b")))
                .isEqualTo(1);
        assertThat(ledger.requeueStrandedEntries(Set.of("node-b")))
                .isZero();
        assertThat(ledger.getQueueCount()).isEqualTo(1);
    }

    @Test
    void testRequeueStrandedEntries_leavesEntryWithoutHolder() {
        var processing = entry("ref-a");
        processing.setProcessingStatus(ProcessingStatus.PROCESSING);
        ledger.updateEntry(processing);

        assertThat(ledger.requeueStrandedEntries(Set.of("node-b")))
                .isZero();
        assertThat(ledger.getProcessingStatus("ref-a"))
                .isEqualTo(ProcessingStatus.PROCESSING);
    }

    @Test
    void testRequeueStrandedEntries_noWorkingNodeKnownPutsNothingBack() {
        ledger.queue(entry("ref-a"));
        claimAs("node-a", 1);

        assertThat(ledger.requeueStrandedEntries(Set.of())).isZero();
        assertThat(ledger.getProcessingStatus("ref-a"))
                .isEqualTo(ProcessingStatus.PROCESSING);
    }

    @Test
    void testRequeueStrandedEntries_leavesFinishedEntry() {
        ledger.queue(entry("ref-a"));
        var claimed = claimAs("node-a", 1).get(0);
        claimed.setProcessingStatus(ProcessingStatus.PROCESSED);
        ledger.updateEntry(claimed);

        assertThat(ledger.requeueStrandedEntries(Set.of("node-b")))
                .isZero();
        assertThat(ledger.getProcessingStatus("ref-a"))
                .isEqualTo(ProcessingStatus.PROCESSED);
    }

    @Test
    void testReleaseClaim_onlyReleasesEntryStillHeldBySameNode() {
        var release = new CrawlerEntryLedger.ReleaseClaim("node-a");

        var heldByOther = entry("ref-a");
        heldByOther.setProcessingStatus(ProcessingStatus.PROCESSING);
        heldByOther.setProcessingNode("node-b");
        assertThat(release.apply(heldByOther)).isNull();

        var finished = entry("ref-a");
        finished.setProcessingStatus(ProcessingStatus.PROCESSED);
        finished.setProcessingNode("node-a");
        assertThat(release.apply(finished)).isNull();

        assertThat(release.apply(null)).isNull();

        var held = entry("ref-a");
        held.setProcessingStatus(ProcessingStatus.PROCESSING);
        held.setProcessingNode("node-a");
        var released = release.apply(held);
        assertThat(released.getProcessingStatus())
                .isEqualTo(ProcessingStatus.QUEUED);
        assertThat(released.getProcessingNode()).isNull();
        // The original is left untouched.
        assertThat(held.getProcessingStatus())
                .isEqualTo(ProcessingStatus.PROCESSING);
    }

    @Test
    void testRequeueStrandedEntries_skipsEntryChangedMeanwhile() {
        // Finished (or put back) by someone else between the scan and the
        // release: left alone, not queued.
        var stubbed = ledgerWithStubbedMaps(spied -> doAnswer(
                inv -> inv.getArgument(
                        1) instanceof CrawlerEntryLedger.ReleaseClaim
                                ? Optional.empty()
                                : inv.callRealMethod())
                                        .when(spied).update(any(), any()));
        stubbed.queue(entry("ref-a"));
        claimAs(stubbed, "node-a", 1);

        assertThat(stubbed.requeueStrandedEntries(Set.of("node-b")))
                .isZero();
        assertThat(stubbed.getProcessingStatus("ref-a"))
                .isEqualTo(ProcessingStatus.PROCESSING);
    }

    @Test
    void testRequeueStrandedEntries_queueFailureLeavesEntryWithItsNode() {
        var failAdds = new AtomicBoolean();
        var manager = new InMemoryCacheManager() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> CacheQueue<T> getCacheQueue(String name, Class<T> type) {
                var queue = spy(super.getCacheQueue(name, type));
                doAnswer(inv -> {
                    if (failAdds.get()) {
                        throw new IllegalStateException("queue down");
                    }
                    return inv.callRealMethod();
                }).when(queue).add(any());
                return queue;
            }
        };
        lenient().when(cluster.getCacheManager()).thenReturn(manager);
        var failing = new CrawlerEntryLedger();
        failing.init(session);
        failing.ensureCurrentLedgerAliasExists();
        failing.queue(entry("ref-a"));
        claimAs(failing, "node-a", 1);
        failAdds.set(true);

        assertThatThrownBy(() -> failing.requeueStrandedEntries(
                Set.of("node-b"))).isInstanceOf(ClusterException.class);
        var kept = failing.getEntry("ref-a").orElseThrow();
        assertThat(kept.getProcessingStatus())
                .isEqualTo(ProcessingStatus.PROCESSING);
        assertThat(kept.getProcessingNode()).isEqualTo("node-a");
    }

    private List<CrawlerEntry> claimAs(String nodeName, int size) {
        return claimAs(ledger, nodeName, size);
    }

    private List<CrawlerEntry> claimAs(
            CrawlerEntryLedger target, String nodeName, int size) {
        lenient().when(localNode.getNodeName()).thenReturn(nodeName);
        try {
            return target.nextQueuedBatch(size);
        } finally {
            lenient().when(localNode.getNodeName())
                    .thenReturn("unit-test-node");
        }
    }

    private static CrawlerEntry entry(String ref) {
        return new CrawlerEntry(ref);
    }
}
