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
import com.norconex.crawler.core.cmd.crawl.pipeline.process.ProcessChildren;
import com.norconex.crawler.core.doc.pipelines.queue.QueuePipelineContext;
import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.session.CrawlerSession;

import lombok.extern.slf4j.Slf4j;

/**
 * Queue orphans for processing.
 */
@Slf4j
public class RequeueOrphansForProcessingStep extends BaseStep {

    public RequeueOrphansForProcessingStep(String id) {
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
        if (ctx.getCrawlEntryLedger().isMaxDocsProcessedReached()) {
            LOG.info("Max documents reached. Not reprocessing orphans. "
                    + "Run the crawler again to resume.");
            return;
        }
        LOG.info("Queueing orphan references for processing...");
        var count = new MutableLong();
        var dangling = new java.util.ArrayList<CrawlerEntry>();
        ctx.getCrawlEntryLedger().forEachBaseline(entry -> {
            if (entry.getParentReference() != null) {
                // A document split off from another one cannot be fetched
                // again by its own reference. What becomes of it is decided
                // by its parent, which is among the orphans, or was
                // processed already. Only one whose parent is nowhere is
                // left, and nothing will ever make it again.
                if (!ProcessChildren.hasKnownParent(session, entry)) {
                    dangling.add(entry);
                }
                return;
            }
            entry.setOrphan(true);
            ctx.getDocPipelines()
                    .getQueuePipeline()
                    .accept(new QueuePipelineContext(session, entry));
            count.increment();
        });

        // Not while going through the baseline: deleting takes them out of it.
        dangling.forEach(
                child -> ProcessChildren.deleteDangling(session, child));
        LOG.info("{} orphan references queued for processing.", count);
    }
}
