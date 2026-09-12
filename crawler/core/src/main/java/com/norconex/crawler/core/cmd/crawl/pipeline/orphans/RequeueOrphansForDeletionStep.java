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
package com.norconex.crawler.core.cmd.crawl.pipeline.orphans;

import org.apache.commons.lang3.mutable.MutableLong;

import com.norconex.crawler.core.cluster.pipeline.BaseStep;
import com.norconex.crawler.core.session.CrawlerSession;

import lombok.extern.slf4j.Slf4j;

/**
 * Queue orphans for deletion.
 * <p>
 * An orphan is a reference the previous run knew about and this run never
 * encountered, which is only evidence that it is gone if this run actually
 * looked everywhere. A crawl that ended early &mdash; because it hit
 * {@code maxDocuments} or {@code maxCrawlDuration} &mdash; did not, and
 * everything it had not reached yet would otherwise be deleted from the
 * target as an orphan.
 * </p>
 */
@Slf4j
public class RequeueOrphansForDeletionStep extends BaseStep {

    public RequeueOrphansForDeletionStep(String id) {
        super(id);
    }

    // returns the type of processing we do on orphans, or null if there
    // is nothing to do
    @Override
    public void execute(CrawlerSession session) {
        var ctx = session.getCrawlContext();

        var orphanCount = ctx.getCrawlEntryLedger().getBaselineCount();
        if (orphanCount == 0) {
            LOG.info("There are no orphans to process.");
            return;
        }

        // References still queued mean the crawl stopped before visiting
        // them, so their absence proves nothing. A stop signal never gets
        // this far (the pipeline breaks out of its step loop), but hitting
        // maxDocuments or maxCrawlDuration ends the crawl normally and does
        // reach here.
        //
        // Erring this way is deliberate. Skipping a legitimate orphan sweep
        // costs a stale document until the next full run; the reverse
        // deletes a live corpus out of the customer's repository.
        if (!ctx.getCrawlEntryLedger().isQueuedEntryEmpty()) {
            LOG.warn("""
                    Crawl ended with {} reference(s) still queued (max \
                    documents, max duration, or an early exit), so orphan \
                    deletion is skipped: references not visited this run \
                    cannot be assumed gone. Run the crawler again to \
                    complete the crawl.""",
                    ctx.getCrawlEntryLedger().getQueuedEntryCount());
            return;
        }

        LOG.info("Queueing orphan references for deletion...");

        var count = new MutableLong();
        ctx.getCrawlEntryLedger().forEachBaseline(entry -> {
            entry.setDeleted(true);
            ctx.getCrawlEntryLedger().queue(entry);
            count.increment();
        });
        LOG.info("{} orphan references queued for deletion.", count);
    }
}
