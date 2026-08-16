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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.norconex.commons.lang.bean.BeanUtil;
import com.norconex.crawler.core.cluster.CacheManager;
import com.norconex.crawler.core.cluster.CacheMap;
import com.norconex.crawler.core.cluster.CacheNames;
import com.norconex.crawler.core.cluster.CacheQueue;
import com.norconex.crawler.core.cluster.ClusterException;
import com.norconex.crawler.core.cluster.QueryFilter;
import com.norconex.crawler.core.session.CrawlerSession;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Tracks document state and any other meta information required
 * for document processing. Acts as a facade over a few processing
 * state-specific data stores. Includes persisting of information
 * necessary for incremental crawls and resumes.
 * </p>
 * <p>
 * The processing of a document has the following stages:
 * </p>
 * <ul>
 *   <li><b>Queued:</b> References extracted from documents are first queued
 *       for processing.</li>
 *   <li><b>Processing:</b> A reference is currently being processed.</li>
 *   <li><b>Processed:</b> A reference has been processed.  If the same
 *       reference is encountered again during the same run, it will be
 *       ignored.</li>
 * </ul>
 * <p>
 *   Once a crawl completes, processed references become "cached" on the
 *   next run and are used to establish deltas and save processing.
 * </p>
 */
@Slf4j
public final class CrawlerEntryLedger {

    private static final String CURRENT_LEDGER_ALIAS_KEY =
            "ledger.current.alias";

    // we use aliases for previous and current ledgers as we can't assume
    // we can physically rename them and copying data over could be
    // too inefficient.
    private static final String LEDGER_A = "ledger_a";
    private static final String LEDGER_B = "ledger_b";

    private CacheMap<CrawlerEntry> currentLedger;
    private CacheMap<CrawlerEntry> baselineLedger;
    private String currentLedgerAlias;
    private String baselineLedgerAlias;
    // The queue owns FIFO ordering; the map owns authoritative entry state.
    // These two structures are intentionally separate: one provides ordering,
    // the other provides status-based queries and key-value access.
    private CacheQueue<String> queue;
    private long totalMaxDocsThisRun;

    // Invoked after a reference is added to the queue. Defaults to a no-op;
    // call setQueuedListener() to attach application-level event publishing.
    // This keeps the ledger free of any dependency on session or events.
    private Consumer<CrawlerEntry> onQueued = e -> {};

    private CacheManager cacheManager;
    private CrawlerSession session;

    /**
     * Gets the current active ledger by reading the alias from session cache.
     * This ensures all cluster nodes use the same ledger after coordinator
     * rotation, even if they initialized before the rotation occurred.
     * Lazily initializes on first access - happens after bootstrap completes.
     */
    @SuppressWarnings("unchecked")
    private CacheMap<CrawlerEntry> getCurrentLedger() {
        var currentAlias = resolveCurrentLedgerAlias();
        if (currentLedger == null
                || !currentAlias.equals(currentLedgerAlias)) {
            currentLedger = (CacheMap<CrawlerEntry>) cacheManager.getCacheMap(
                    currentAlias,
                    session.getCrawlContext().getCrawlEntryType());
            currentLedger.loadAll();
            currentLedgerAlias = currentAlias;
            LOG.debug("Lazy-initialized current ledger to: {}", currentAlias);
        }
        return currentLedger;
    }

    /**
     * Gets the baseline (previous) ledger for delta detection.
     * Lazily initializes on first access.
     */
    @SuppressWarnings("unchecked")
    private CacheMap<CrawlerEntry> getBaselineLedger() {
        var currentAlias = resolveCurrentLedgerAlias();
        var previousAlias = LEDGER_A.equals(currentAlias) ? LEDGER_B : LEDGER_A;
        if (baselineLedger == null
                || !previousAlias.equals(baselineLedgerAlias)) {
            if (cacheManager.cacheExists(previousAlias)) {
                baselineLedger =
                        (CacheMap<CrawlerEntry>) cacheManager.getCacheMap(
                                previousAlias,
                                session.getCrawlContext()
                                        .getCrawlEntryType());
                baselineLedger.loadAll();
                baselineLedgerAlias = previousAlias;
            } else {
                baselineLedger = null;
                baselineLedgerAlias = null;
            }
            LOG.debug("Lazy-initialized baseline ledger to: {}",
                    previousAlias);
        }
        return baselineLedger;
    }

    private String resolveCurrentLedgerAlias() {
        return cacheManager.getCrawlSessionCache()
                .get(CURRENT_LEDGER_ALIAS_KEY)
                .orElse(LEDGER_A);
    }

    /**
     * Registers a listener that is called each time a new reference is
     * successfully added to the queue. Use this to decouple application-level
     * event publishing from the ledger.
     *
     * @param listener callback invoked with the queued {@link CrawlerEntry}
     */
    public void setQueuedListener(Consumer<CrawlerEntry> listener) {
        onQueued = listener != null ? listener : e -> {};
    }

    /**
     * Ensures the current ledger alias exists in session cache.
     * Sets default (LEDGER_A) if not present. This should be called
     * by the coordinator before any ledger rotation to establish the
     * initial state for all cluster nodes.
     */
    public void ensureCurrentLedgerAliasExists() {
        var sessionCache = cacheManager.getCrawlSessionCache();
        sessionCache.computeIfAbsent(CURRENT_LEDGER_ALIAS_KEY,
                k -> LEDGER_A);
    }

    public void init(CrawlerSession session) {
        LOG.info("Initializing crawl entry ledger...");
        this.session = session;
        cacheManager = session.getCluster().getCacheManager();

        // Caches:
        // currentLedger and baselineLedger are lazily initialized on first
        // access to ensure they're reference the correct ledger after bootstrap
        // rotation completes (coordinator rotates ledgers during bootstrap,
        // but workers shouldn't cache the reference until after rotation)
        queue = cacheManager.getCacheQueue(CacheNames.REFERENCE_QUEUE,
                String.class);

        // Max docs
        long runMaxDocs =
                session.getCrawlContext().getCrawlConfig().getMaxDocuments();
        totalMaxDocsThisRun = runMaxDocs;
        var resumed = session.isResumed();
        if (resumed && runMaxDocs > -1) {
            var current = getCurrentLedger();
            totalMaxDocsThisRun += current.count(
                    statusQueryFilter(ProcessingStatus.PROCESSED));
            LOG.info("""
                    An additional maximum of {} processed documents is
                    added to this resumed session, for a maximum total of {}.
                    """, runMaxDocs,
                    totalMaxDocsThisRun);
        }

        LOG.info("Done initializing crawl entry ledger. Queued entries: {}",
                getQueuedEntryCount());
    }

    /**
     * Re-queues entries that were in QUEUED state from a previous run.
     * This is needed when the persistent queue fails to restore items
     * (e.g., due to partition ownership changes across restarts).
     * Called by CrawlerEntryLedgerBootstrapper during RUN LEVEL initialization.
     * @return the number of entries re-queued
     */
    public int requeueQueuedEntries() {
        var current = getCurrentLedger();
        var queuedIt = current.queryIterator(
                statusQueryFilter(ProcessingStatus.QUEUED));

        // Collect entries and sort by queuedAt (nulls last) to preserve
        // FIFO ordering as much as possible when restoring queue.
        var entries = new ArrayList<CrawlerEntry>();
        while (queuedIt.hasNext()) {
            entries.add(queuedIt.next());
        }

        entries.sort((a, b) -> {
            var qa = a.getQueuedAt();
            var qb = b.getQueuedAt();
            if (qa == null && qb == null) {
                return 0;
            }
            if (qa == null) {
                return 1; // put nulls last
            }
            if (qb == null) {
                return -1;
            }
            return qa.compareTo(qb);
        });

        var requeuedCount = 0;
        for (var entry : entries) {
            queue.add(entry.getReference());
            requeuedCount++;
        }
        LOG.info("Re-queued {} previously QUEUED entries into queue.",
                requeuedCount);
        return requeuedCount;
    }

    /**
     * Updates an entry in the ledger, maintaining the status counters.
     * @param entry the entry to update
     * @return the previous entry if it existed
     */
    public Optional<CrawlerEntry> updateEntry(CrawlerEntry entry) {
        var reference = entry.getReference();
        var current = getCurrentLedger();
        var previous = current.get(reference);
        current.put(reference, entry);
        if (ProcessingStatus.PROCESSED.is(entry.getProcessingStatus())) {
            var baseline = getBaselineLedger();
            if (baseline != null) {
                baseline.remove(reference);
            }
        }
        return previous;
    }

    /**
     * Removes an entry from the ledger, updating the status counters.
     * @param reference the reference to remove
     * @return the removed entry if it existed
     */
    public Optional<CrawlerEntry> removeEntry(String reference) {
        var current = getCurrentLedger();
        var entry = current.get(reference);
        if (entry.isPresent()) {
            entry.get().getProcessingStatus();
            current.remove(reference);
        }
        return entry;
    }

    /**
     * Whether a reference exists in the current ledger.
     * @param ref document reference
     * @return {@code true} if existing
     */
    public boolean exists(String ref) {
        return getCurrentLedger().containsKey(ref);
    }

    /**
     * Gets a reference processing status. If a document does not exist,
     * the processing status will be {@link ProcessingStatus#UNTRACKED}.
     * @param ref document reference
     * @return the processing status
     */
    public ProcessingStatus getProcessingStatus(String ref) {
        return getCurrentLedger().get(ref)
                .map(CrawlerEntry::getProcessingStatus)
                .orElse(ProcessingStatus.UNTRACKED);
    }

    /**
     * Gets the current ledger entry for the given reference, if it exists.
     * @param ref document reference
     * @return the current crawl entry, or empty if not found
     */
    public Optional<CrawlerEntry> getEntry(String ref) {
        return getCurrentLedger().get(ref);
    }

    //--- Queue ---

    public boolean isQueueEmpty() {
        return queue.isEmpty();
    }

    public long getQueueCount() {
        // Keep this method for backward compatibility with existing callers,
        // but avoid queue-store size calls that may block during startup.
        return getQueuedEntryCount();
    }

    /**
     * Returns the number of entries tracked as {@link ProcessingStatus#QUEUED}
     * in the current ledger.
     * <p>
     * Unlike the physical queue size, this value comes from the authoritative
     * ledger state and avoids expensive queue-store size operations.
     * </p>
     * @return queued entry count from the ledger
     */
    public long getQueuedEntryCount() {
        return countByStatus(ProcessingStatus.QUEUED);
    }

    /**
     * Returns whether the current ledger has any entries still tracked in
     * {@link ProcessingStatus#QUEUED} state.
     * @return {@code true} if no queued entries remain in the ledger
     */
    public boolean isQueuedEntryEmpty() {
        return getQueuedEntryCount() == 0;
    }

    public void clearQueue() {
        queue.clear();
        getCurrentLedger().delete(statusQueryFilter(ProcessingStatus.QUEUED));
    }

    /**
     * Clears QUEUED entries from the active ledger without issuing a
     * physical distributed queue clear.
     */
    public void clearQueuedEntriesInLedger() {
        getCurrentLedger().delete(statusQueryFilter(ProcessingStatus.QUEUED));
    }

    public void forEachQueued(Consumer<CrawlerEntry> c) {
        forEachProcessingStatus(ProcessingStatus.QUEUED, c);
    }

    public void queue(@NonNull CrawlerEntry crawlEntry) {
        var current = getCurrentLedger();
        var reference = crawlEntry.getReference();
        if (current.containsKey(reference)) {
            LOG.debug("Reference already accounted for: {}",
                    reference);
            return;
        }

        var queuedEntry = BeanUtil.clone(crawlEntry);

        // Store the full entry in the ledger and only the reference
        // in the queue
        queuedEntry.setProcessingStatus(ProcessingStatus.QUEUED);
        current.put(reference, queuedEntry);
        try {
            queue.add(reference);
        } catch (RuntimeException e) {
            // Keep ledger and queue consistent when queue store is unavailable.
            current.remove(reference);
            throw new ClusterException(
                    "Failed to queue reference '%s'; ledger update rolled back."
                            .formatted(reference),
                    e);
        }
        LOG.debug("Queued for processing: {}", reference);
        onQueued.accept(queuedEntry);
    }

    public boolean requeueEntry(@NonNull String reference) {
        return getEntry(reference)
                .map(this::requeueEntry)
                .orElseGet(() -> {
                    LOG.debug("Cannot requeue missing reference: {}",
                            reference);
                    return false;
                });
    }

    public boolean requeueEntry(@NonNull CrawlerEntry crawlEntry) {
        var reference = crawlEntry.getReference();
        var current = getCurrentLedger();
        var entryOpt = current.get(reference);
        if (entryOpt.isEmpty()) {
            LOG.debug("Cannot requeue missing reference: {}", reference);
            return false;
        }

        var existingEntry = entryOpt.get();
        if (ProcessingStatus.QUEUED.is(existingEntry.getProcessingStatus())) {
            LOG.debug("Reference already queued for processing: {}",
                    reference);
            return false;
        }
        if (ProcessingStatus.PROCESSING
                .is(existingEntry.getProcessingStatus())) {
            LOG.debug("Reference already processing: {}", reference);
            return false;
        }

        var queuedEntry = BeanUtil.clone(crawlEntry);
        queuedEntry.setProcessingStatus(ProcessingStatus.QUEUED);
        current.put(reference, queuedEntry);
        try {
            queue.add(reference);
        } catch (RuntimeException e) {
            // Restore previous entry status if queue write fails.
            current.put(reference, existingEntry);
            throw new ClusterException(
                    "Failed to re-queue reference '%s'; ledger update rolled back."
                            .formatted(reference),
                    e);
        }
        LOG.debug("Re-queued tracked reference for processing: {}",
                reference);
        onQueued.accept(queuedEntry);
        return true;
    }

    public List<CrawlerEntry> nextQueuedBatch(int batchSize) {
        var nodeName = session.getCluster().getLocalNode().getNodeName();

        // Always get the current ledger dynamically to ensure we're using
        // the same ledger as the coordinator, even if it rotated after
        // this worker node initialized
        var activeLedger = getCurrentLedger();

        if (LOG.isTraceEnabled()) {
            var queuedCount = activeLedger.count(
                    statusQueryFilter(ProcessingStatus.QUEUED));
            LOG.trace("[{}] CrawlerEntryLedger.nextQueuedBatch(batchSize={}) "
                    + "called. queuedCount={}.",
                    nodeName, batchSize, queuedCount);
        }

        // Poll references from queue
        var references = queue.pollBatch(batchSize);
        var batch = new java.util.ArrayList<CrawlerEntry>(references.size());

        // For each reference, fetch and update the entry in the ledger
        for (String reference : references) {
            var entryOpt = activeLedger.get(reference);
            if (entryOpt.isPresent()) {
                var entry = entryOpt.get();
                // Update status to PROCESSING
                entry.setProcessingStatus(ProcessingStatus.PROCESSING);
                activeLedger.put(reference, entry);
                batch.add(entry);
            } else {
                LOG.warn("[{}] Reference {} polled from queue but not "
                        + "found in ledger.", nodeName, reference);
            }
        }

        if (LOG.isTraceEnabled()) {
            LOG.trace("[{}] CrawlerEntryLedger.nextQueuedBatch returning "
                    + "{} entries.", nodeName, batch.size());
        }
        return batch;
    }

    /**
     * Re-queues entries that were in PROCESSING state from a previous run.
     * stopped. These entries were pulled from the queue but not completed,
     * so they need to be queued again on resume to avoid losing them.
     * Updates their status back to QUEUED.
     *
     * @return the number of entries re-queued
     */
    public int requeueProcessingEntries() {
        var current = getCurrentLedger();
        var inFlight = current.queryIterator(
                statusQueryFilter(ProcessingStatus.PROCESSING));
        var requeuedCount = 0;
        while (inFlight.hasNext()) {
            var entry = inFlight.next();
            var reference = entry.getReference();
            entry.setProcessingStatus(ProcessingStatus.QUEUED);
            current.put(reference, entry);
            try {
                queue.add(reference);
            } catch (RuntimeException e) {
                // Restore PROCESSING state when queue write fails.
                entry.setProcessingStatus(ProcessingStatus.PROCESSING);
                current.put(reference, entry);
                throw new ClusterException(
                        "Failed to re-queue PROCESSING reference '%s'; ledger update rolled back."
                                .formatted(reference),
                        e);
            }
            requeuedCount++;
        }
        return requeuedCount;
    }

    //    public Optional<CrawlerEntry> nextQueued() {
    //        var query = statusQueryFilter(ProcessingStatus.QUEUED);
    //        var queuedEntries = currentLedger.queryIterator(query);
    //        if (!queuedEntries.hasNext()) {
    //            return Optional.empty();
    //        }
    //        var entry = queuedEntries.next();
    //        var reference = entry.getReference();
    //        return claimQueuedEntry(reference);
    //    }

    //--- Processing ---

    public long getProcessingCount() {
        return getCurrentLedger().count(
                statusQueryFilter(ProcessingStatus.PROCESSING));
    }

    public boolean isProcessingEmpty() {
        return getProcessingCount() == 0;
    }

    public void forEachProcessing(Consumer<CrawlerEntry> c) {
        forEachProcessingStatus(ProcessingStatus.PROCESSING, c);
    }

    private void forEachProcessingStatus(
            ProcessingStatus status, Consumer<CrawlerEntry> c) {
        getCurrentLedger().queryIterator(
                statusQueryFilter(status))
                .forEachRemaining(c::accept);
    }

    //--- Processed ---

    public long getProcessedCount() {
        return getCurrentLedger().count(
                statusQueryFilter(ProcessingStatus.PROCESSED));
    }

    public boolean isProcessedEmpty() {
        return getProcessedCount() == 0;
    }

    public void forEachProcessed(Consumer<CrawlerEntry> c) {
        forEachProcessingStatus(ProcessingStatus.PROCESSED, c);
    }

    //--- Previous baseline crawl entries ---

    public long getBaselineCount() {
        var baseline = getBaselineLedger();
        return baseline == null ? 0L : baseline.size();
    }

    public void forEachBaseline(Consumer<CrawlerEntry> c) {
        var baseline = getBaselineLedger();
        if (baseline != null) {
            baseline.forEach((k, v) -> c.accept(v));
        }
    }

    public Optional<CrawlerEntry> getBaselineEntry(String id) {
        var baseline = getBaselineLedger();
        return baseline == null
                ? Optional.empty()
                : baseline.get(id);
    }

    //--- Misc. ---

    /**
     * Gets all entries matching the given processing status.
     * @param status the processing status to match
     * @return matching entries
     */
    public Iterator<CrawlerEntry> getEntriesByStatus(ProcessingStatus status) {
        return getCurrentLedger().queryIterator(statusQueryFilter(status));
    }

    /**
     * Counts entries with the given processing status.
     * Uses an efficient O(1) counter instead of executing a query.
     * @param status the processing status to count
     * @return count of entries with the given status
     */
    public long countByStatus(ProcessingStatus status) {
        return getCurrentLedger().count(statusQueryFilter(status));
    }

    /**
     * Deletes all entries with the given processing status.
     * Also updates the status counter accordingly.
     * @param status the processing status of entries to delete
     */
    public void deleteByStatus(ProcessingStatus status) {
        getCurrentLedger().delete(statusQueryFilter(status));
    }

    /**
     * Archives the current ledger by making it the baseline and resetting
     * the current ledger to empty. <strong>Must only be called by the
     * coordinator node.</strong> In a multi-node cluster, calling this on
     * a non-coordinator node is a no-op (logged as a warning).
     */
    @SuppressWarnings("unchecked")
    public void archiveCurrentLedger() {
        if (!session.getCluster().getLocalNode().isCoordinator()) {
            LOG.warn("archiveCurrentLedger() called on non-coordinator node; "
                    + "ignoring.");
            return;
        }
        // Clear previous baseline ledger (old current run) if it was initialized
        var baseline = getBaselineLedger();
        if (baseline != null) {
            clearLedgerEntries(baseline, "previous baseline");
        }

        // Flip alias for current ledger in session cache
        var sessionCache = cacheManager.getCrawlSessionCache();
        var currentAlias =
                sessionCache.get(CURRENT_LEDGER_ALIAS_KEY).orElse(null);
        var newAlias = LEDGER_A.equals(currentAlias) ? LEDGER_B : LEDGER_A;
        sessionCache.put(CURRENT_LEDGER_ALIAS_KEY, newAlias);
        var previousAlias = LEDGER_A.equals(newAlias) ? LEDGER_B : LEDGER_A;

        // Update cached references if they were already initialized
        // (this only matters for coordinator which calls this method)
        if (!newAlias.equals(currentAlias)) {
            var entryType = session.getCrawlContext().getCrawlEntryType();
            currentLedger =
                    (CacheMap<CrawlerEntry>) cacheManager.getCacheMap(newAlias,
                            entryType);
            currentLedgerAlias = newAlias;
            baselineLedger = cacheManager.cacheExists(previousAlias)
                    ? (CacheMap<CrawlerEntry>) cacheManager
                            .getCacheMap(previousAlias, entryType)
                    : null;
            baselineLedgerAlias = baselineLedger == null ? null : previousAlias;
            clearLedgerEntries(currentLedger, "new current");
        } else {
            LOG.info("Alias unchanged; caches not recreated.");
        }
        LOG.info("Ledger rotation complete: current={}, previous={}", newAlias,
                previousAlias);
    }

    public boolean isMaxDocsProcessedReached() {
        //TODO replace check for "processedCount" vs "maxDocuments"
        // with event counts vs max committed, max processed, max etc...
        // Check if we merge with StopCrawlerOnMaxEventListener
        // or if we remove maxDocument in favor of the listener.
        // what about clustering?
        var isMax = totalMaxDocsThisRun > -1
                && getProcessedCount() >= totalMaxDocsThisRun;
        if (isMax) {
            LOG.info("Maximum documents reached for this crawling "
                    + "session: {}", totalMaxDocsThisRun);
        }
        return isMax;
    }

    //--- Private methods ------------------------------------------------------

    private void clearLedgerEntries(
            CacheMap<CrawlerEntry> ledger, String ledgerLabel) {
        var keys = new java.util.ArrayList<String>();
        ledger.forEach((key, value) -> keys.add(key));
        if (keys.isEmpty()) {
            LOG.info("Cleared {} ledger: already empty.", ledgerLabel);
            return;
        }
        keys.forEach(ledger::remove);
        LOG.info("Cleared {} ledger by removing {} entries.",
                ledgerLabel, keys.size());
    }

    //    /**
    //     * Atomically claims a QUEUED entry for processing, updating its status
    //     * and timestamp.
    //     * Updates status counters if successful.
    //     * Uses atomic replace() to avoid serialization issues with BiFunction.
    //     * @param reference the reference to claim
    //     * @return the updated entry if successfully claimed, otherwise empty
    //     */
    //    private Optional<CrawlerEntry> claimQueuedEntry(String reference) {
    //        var nodeName = session.getCluster().getLocalNode().getNodeName();
    //        var current = currentLedger.get(reference);
    //        if (current.isEmpty()) {
    //            LOG.trace("[{}] claimQueuedEntry({}) current empty.",
    //                    nodeName, reference);
    //            return Optional.empty();
    //        }
    //
    //        var entry = current.get();
    //        if (entry.getProcessingStatus() != ProcessingStatus.QUEUED) {
    //            LOG.trace("[{}] claimQueuedEntry({}) status is {} (not QUEUED).",
    //                    nodeName, reference, entry.getProcessingStatus());
    //            return Optional.empty();
    //        }
    //
    //        var updatedEntry = BeanUtil.clone(entry);
    //        updatedEntry.setProcessingStatus(ProcessingStatus.PROCESSING);
    //        updatedEntry.setProcessingAt(Instant.now().atZone(ZoneOffset.UTC));
    //
    //        var replaced = currentLedger.replace(
    //                reference, entry, updatedEntry);
    //
    //        if (replaced) {
    //            LOG.trace("[{}] claimQueuedEntry({}) SUCCESS. "
    //                    + "queuedCount={} processingCount={}.",
    //                    nodeName, reference,
    //                    currentLedger.count(
    //                            statusQueryFilter(ProcessingStatus.QUEUED)),
    //                    currentLedger.count(
    //                            statusQueryFilter(ProcessingStatus.PROCESSING)));
    //            return Optional.of(updatedEntry);
    //        }
    //
    //        LOG.trace(
    //                "[{}] claimQueuedEntry({}) FAILED replace (concurrent update).",
    //                nodeName, reference);
    //        return Optional.empty();
    //    }

    private QueryFilter statusQueryFilter(ProcessingStatus status) {
        return QueryFilter.of(
                CrawlerEntry.Fields.processingStatus, status.name());
    }

    //    private String fromOrderedQueuedQuery() {
    //        // Use Java class name for queries - the cache implementation
    //        // handles serialization/deserialization
    //        return "FROM %s WHERE processingStatus = '%s' ORDER BY %s"
    //                .formatted(
    //                        CrawlerEntry.class.getName(),
    //                        ProcessingStatus.QUEUED.name(),
    //                        CrawlerEntry.Fields.queuedAt);
    //    }
    //
    //    // To stream in slightly larger batches for efficiency on multi-nodes
    //    private int bonifiedBatchSize(int batchSize) {
    //        var nodeCnt = session.getCluster().getNodeCount();
    //        // When multiple nodes are present, scan deeper than the local
    //        // batch size so slower nodes can still discover unclaimed
    //        // entries further down the global queue. Keep it modest to
    //        // avoid scanning the entire cache on every poll, but also
    //        // not so aggressive that the first node claims everything.
    //        int overscanFactor;
    //        if (nodeCnt <= 1) {
    //            overscanFactor = 1;
    //        } else if ((nodeCnt == 2) || (nodeCnt > 4)) {
    //            // Reduced from 8 to 3 to give both nodes fairer access
    //            overscanFactor = 3;
    //        } else {
    //            overscanFactor = 4;
    //        }
    //        var bonified = Math.max(batchSize, batchSize * overscanFactor);
    //        LOG.trace(
    //                "[{}] bonifiedBatchSize({}) -> {} (nodeCnt={}, overscanFactor={}).",
    //                session.getCluster().getLocalNode().getNodeName(),
    //                batchSize, bonified, nodeCnt, overscanFactor);
    //        return bonified;
    //    }

}
