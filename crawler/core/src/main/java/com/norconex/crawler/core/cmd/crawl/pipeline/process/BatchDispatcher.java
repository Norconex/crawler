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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.session.CrawlerSession;

import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Builder
public class BatchDispatcher {
    /**
     * Maximum number of references for a node to poll at once from the
     * queue for processing.
     */
    private final int maxBatchSize;
    /**
     * Threshold below which a new reference batch is poll from the queue,
     * so the local node queue never gets dry.
     */
    private final int lowWatermark;
    private final CrawlerSession session;
    /**
     * How many times in a row reading the queue may fail before the failure
     * is rethrown (and the crawler stops). A single failed read is often
     * transient (e.g., a store read racing with background compaction),
     * while a persistent one would otherwise loop forever.
     */
    @Builder.Default
    private final int maxReadAttempts = 5;
    /** Base pause between failed queue reads (multiplied per attempt). */
    @Builder.Default
    private final long readRetryPauseMs = 200;

    private final BlockingQueue<CrawlerEntry> localQueue =
            new LinkedBlockingQueue<>();
    private final Object refillLock = new Object();

    /**
     * Takes the next entry to process, or <code>null</code> if none are
     * available. A failure reading the queue is retried a few times before
     * being rethrown.
     * @return next entry or <code>null</code>
     * @throws InterruptedException if interrupted while waiting to retry
     */
    public CrawlerEntry take() throws InterruptedException {
        for (var attempt = 1;; attempt++) {
            try {
                return doTake();
            } catch (RuntimeException e) {
                if (attempt >= maxReadAttempts) {
                    throw e;
                }
                LOG.warn("Could not read the crawl queue (attempt {} of {}); "
                        + "retrying: {}", attempt, maxReadAttempts,
                        e.toString());
                Thread.sleep(readRetryPauseMs * attempt);
            }
        }
    }

    private CrawlerEntry doTake() {
        var ledger = session.getCrawlContext().getCrawlEntryLedger();
        var nodeName = session.getCluster().getLocalNode().getNodeName();
        while (true) {
            var entry = localQueue.poll();
            if (entry != null) {
                LOG.trace("[{}] BatchDispatcher.take() returning local "
                        + "entry {}. localQueueSize={}.",
                        nodeName, entry.getReference(), localQueue.size());
                maybeRefill();
                return entry;
            }
            // Only one thread should try to refill when empty
            synchronized (refillLock) {
                if (localQueue.isEmpty()) {
                    var batchSize = computeBatchSize();
                    var queueCount = ledger.getQueuedEntryCount();
                    LOG.trace("[{}] BatchDispatcher.take() refilling from "
                            + "global queue (batchSize={}, queueCount={}).",
                            nodeName, batchSize, queueCount);
                    var batch = ledger.nextQueuedBatch(batchSize);
                    LOG.trace("[{}] BatchDispatcher.take() got batch of {} "
                            + "entries from global queue.",
                            nodeName, batch.size());
                    if (!batch.isEmpty()) {
                        localQueue.addAll(batch);
                        continue;
                    }
                }
            }
            LOG.trace("[{}] BatchDispatcher.take() found no entries in local "
                    + "or global queue.",
                    nodeName);
            // Return null immediately if queue is empty
            // Let CrawlerActivityChecker handle idle timeout logic
            return null;
        }
    }

    private void maybeRefill() {
        var ledger = session.getCrawlContext().getCrawlEntryLedger();
        var nodeName = session.getCluster().getLocalNode().getNodeName();
        if (localQueue.size() <= lowWatermark) {
            synchronized (refillLock) {
                if (localQueue.size() <= lowWatermark) {
                    var batchSize = computeBatchSize();
                    var queueCount = ledger.getQueuedEntryCount();
                    LOG.trace("""
                        [{}] BatchDispatcher.maybeRefill() refilling \
                        (batchSize={}, queueCount={}, \
                        localQueueSize(before)={}).""",
                            nodeName, batchSize, queueCount, localQueue.size());
                    var batch = ledger.nextQueuedBatch(batchSize);
                    LOG.trace("[{}] BatchDispatcher.maybeRefill() got batch of "
                            + "{} entries.",
                            nodeName, batch.size());
                    localQueue.addAll(batch);
                    LOG.trace("[{}] BatchDispatcher.maybeRefill() "
                            + "localQueueSize(after)={}",
                            nodeName, localQueue.size());
                }
            }
        }
    }

    public int localQueueSize() {
        return localQueue.size();
    }

    private int computeBatchSize() {
        var ledger = session.getCrawlContext().getCrawlEntryLedger();
        var queueCount = ledger.getQueuedEntryCount();
        var nodeCount = session.getCluster().getNodeCount();
        var localQueueSize = localQueue.size();

        // Calculate total work available (global + what this node already has)
        var totalAvailable = queueCount + localQueueSize;

        // This node's fair share of the total work
        var nodeFairShare =
                (int) Math.ceil((double) totalAvailable / nodeCount);

        // How much more should this node grab?
        // (fair share minus what it already has locally)
        var remainingAllowance = Math.max(0, nodeFairShare - localQueueSize);

        // Limit by: what's available in queue, this node's remaining allowance, and max batch
        var batchSize = (int) Math.max(1,
                Math.min(maxBatchSize,
                        Math.min(queueCount, remainingAllowance)));

        LOG.trace("[{}] BatchDispatcher.computeBatchSize() queueCount={}, "
                + "nodeCount={}, localQueueSize={}, totalAvailable={}, "
                + "nodeFairShare={}, remainingAllowance={}, maxBatchSize={} "
                + "=> batchSize={}.",
                session.getCluster().getLocalNode().getNodeName(),
                queueCount, nodeCount, localQueueSize, totalAvailable,
                nodeFairShare, remainingAllowance, maxBatchSize, batchSize);
        return batchSize;
    }

}
