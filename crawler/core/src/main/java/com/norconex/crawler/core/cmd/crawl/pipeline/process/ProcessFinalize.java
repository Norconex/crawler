/* Copyright 2023-2026 Norconex Inc.
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

import static com.norconex.crawler.core.doc.operations.spoil.impl.GenericSpoiledReferenceStrategizerConfig.DEFAULT_FALLBACK_STRATEGY;

import java.util.HashSet;
import java.util.Optional;

import com.norconex.commons.lang.bean.BeanUtil;
import com.norconex.crawler.core.doc.operations.spoil.SpoiledReferenceStrategy;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.core.ledger.ProcessingStatus;

import lombok.extern.slf4j.Slf4j;

@Slf4j
final class ProcessFinalize {

    private ProcessFinalize() {
    }

    static void execute(ProcessContext ctx) {

        // only finalize a record if not already finalized and if
        // there is a doc record (meaning a reference was loaded).
        if (ctx.finalized() || ctx.docContext() == null) {
            return;
        }

        ctx.finalized(true);

        var crawlCtx = ctx.crawlSession().getCrawlContext();
        var docCtx = ctx.docContext();
        var currentEntry = docCtx.getCurrentCrawlEntry();

        //        if (docCtx.getDoc() == null) {
        //            docCtx. doc(new Doc(docCtx.getReference());
        //        }
        //        var doc = ctx.doc();
        //        var cachedDocRecord = doc.getCachedDocContext();

        //--- Ensure we have a state -------------------------------------------
        if (currentEntry.getProcessingOutcome() == null) {
            LOG.warn("Processing outcome is unknown for \"{}\". "
                    + "This should not happen. Assuming bad status.",
                    docCtx.getReference());
            currentEntry.setProcessingOutcome(ProcessingOutcome.BAD_STATUS);
        }

        try {

            // important to call this before copying properties further down
            var beforeDoc = docCtx.getDoc();
            if (beforeDoc != null) {
                ctx.crawlSession().fire(CrawlerEvent.builder()
                        .name(CrawlerEvent.DOCUMENT_FINALIZING_BEGIN)
                        .crawlSession(ctx.crawlSession())
                        .source(beforeDoc)
                        .build());
            }

            //--- If doc crawl was incomplete, set missing info from cache -----
            // If document is not new or modified, it did not go through
            // the entire crawl life cycle for a document so maybe not all info
            // could be gathered for a reference.  Since we do not want to lose
            // previous information when the crawl was effective/good
            // we copy it all that is non-null from cache.
            if (!docCtx.getCurrentCrawlEntry().getProcessingOutcome()
                    .isNewOrModified()
                    && docCtx.getPreviousCrawlEntry() != null) {
                //TODO maybe new CrawlData instances should be initialized with
                // some of cache data available instead?
                BeanUtil.copyPropertiesOverNulls(
                        docCtx.getCurrentCrawlEntry(),
                        docCtx.getPreviousCrawlEntry());
            }

            dealWithBadState(ctx);

        } catch (Exception e) {
            LOG.error(
                    "Could not finalize processing of: {} ({})",
                    docCtx.getReference(), e.getMessage(), e);
        }

        //--- Mark reference as Processed --------------------------------------
        try {
            currentEntry.setProcessingStatus(ProcessingStatus.PROCESSED);
            crawlCtx.getCrawlEntryLedger().updateEntry(currentEntry);
            markReferenceVariationsAsProcessed(ctx);

        } catch (Exception e) {
            LOG.error(
                    "Could not mark reference as processed: {} ({})",
                    docCtx.getReference(), e.getMessage(), e);
        } finally {
            var afterDoc = docCtx.getDoc();
            if (afterDoc != null) {
                ctx.crawlSession().fire(CrawlerEvent.builder()
                        .name(CrawlerEvent.DOCUMENT_FINALIZING_END)
                        .crawlSession(ctx.crawlSession())
                        .source(afterDoc)
                        .build());
            }
        }

        try {
            docCtx.getDoc().getInputStream().dispose();
        } catch (Exception e) {
            LOG.error("Could not dispose of resources.", e);
        }
    }

    // passing CrawlDoc here because sometimes it can be null in context
    // and we do not want to set one on context.
    private static void dealWithBadState(ProcessContext ctx) {
        var crawlCtx = ctx.crawlSession().getCrawlContext();
        var docCtx = ctx.docContext();
        docCtx.getDoc();
        var currentEntry = docCtx.getCurrentCrawlEntry();

        //--- Deal with bad states (if not already deleted) ----------------
        if (!currentEntry.getProcessingOutcome().isGoodState()
                && !currentEntry.getProcessingOutcome()
                        .isOneOf(ProcessingOutcome.DELETED)) {

            var previousEntry = docCtx.getPreviousCrawlEntry();
            if (previousEntry != null
                    && previousEntry.getProcessingOutcome() == null) {
                LOG.warn("""
                        Got a cached document from previous run with no \
                        crawl state associated. This should not happen. \
                        Will treat it as ERROR. Doc info: {}""",
                        docCtx);
                previousEntry.setProcessingOutcome(ProcessingOutcome.ERROR);
                return;
            }

            //TODO If duplicate, consider it as spoiled if a cache version
            // exists in a good state.
            // This involves elaborating the concept of duplicate
            // or "reference change" in this core project. Otherwise there
            // is the slim possibility right now that a Collector
            // implementation marking references as duplicate may
            // generate orphans (which may be caught later based
            // on how orphans are handled, but they should not be ever
            // considered orphans in the first place).
            // This could remove the need for the
            // markReferenceVariationsAsProcessed(...) method

            var strategy = Optional.ofNullable(
                    crawlCtx.getCrawlConfig()
                            .getSpoiledReferenceStrategizer())
                    .map(srs -> srs.resolveSpoiledReferenceStrategy(
                            docCtx.getReference(),
                            docCtx.getCurrentCrawlEntry()
                                    .getProcessingOutcome()))
                    .orElse(DEFAULT_FALLBACK_STRATEGY);

            if (strategy == SpoiledReferenceStrategy.IGNORE) {
                LOG.debug(
                        "Ignoring spoiled reference: {}",
                        docCtx.getReference());
            } else if (strategy == SpoiledReferenceStrategy.DELETE) {
                if (mayExistInTarget(previousEntry)) {
                    ProcessDelete.execute(ctx);
                }
            } else // GRACE_ONCE:
            // Delete on a second consecutive bad run only: a reference that
            // was fine last time gets one crawl of grace.
            if (previousEntry != null && mayExistInTarget(previousEntry)) {
                if (!previousEntry.getProcessingOutcome().isGoodState()) {
                    ProcessDelete.execute(ctx);
                } else {
                    LOG.debug("""
                            This spoiled reference is\s\
                            being graced once (will be deleted\s\
                            next time if still spoiled): {}""",
                            docCtx.getReference());
                }
            }
        }
    }

    /**
     * Whether deleting this reference could still match anything in the
     * target repository, judged from what the previous run did with it.
     * <p>
     * Two previous outcomes mean there is nothing to delete:
     * </p>
     * <ul>
     *   <li>{@link ProcessingOutcome#DELETED} &mdash; already deleted.</li>
     *   <li>{@link ProcessingOutcome#REJECTED} &mdash; deliberately not
     *       committed. Either it was never sent under this reference, or it
     *       was sent in some earlier run and the delete already went out when
     *       it first turned rejected. Either way another one is redundant.</li>
     * </ul>
     * <p>
     * The rejected case is not hypothetical, and a file system folder is the
     * clearest example. A folder is a perfectly legitimate ledger entry: it is
     * queued, traversed, depth-tracked, read back from the baseline to spot
     * descendants that have gone missing, and it can be rejected by a rule
     * like any other reference. What it does not do is yield a document of its
     * own, so it ends every run rejected &mdash; which meant a delete for it
     * went to the customer's repository on every recrawl after the first, for
     * something that had never been sent there, inflating the deletion count
     * reported alongside it.
     * </p>
     * <p>
     * Note that this turns on what the previous run <em>committed</em>, not on
     * what kind of thing the reference is. A folder that is also a file, or a
     * document that was committed and is only now rejected by a new rule, has
     * a good previous outcome and is still deleted &mdash; correctly.
     * </p>
     */
    private static boolean mayExistInTarget(CrawlerEntry previousEntry) {
        if (previousEntry == null) {
            // Never seen before this run, so nothing was ever sent for it.
            return false;
        }
        return !previousEntry.getProcessingOutcome().isOneOf(
                ProcessingOutcome.DELETED,
                ProcessingOutcome.REJECTED);
    }

    private static void markReferenceVariationsAsProcessed(
            ProcessContext ctx) {
        // Mark reference trail as processed
        var crawlEntry = ctx.docContext().getCurrentCrawlEntry();
        new HashSet<>(
                ctx.docContext().getCurrentCrawlEntry().getReferenceTrail())
                        .forEach(ref -> {
                            var trailEntry = crawlEntry.withReference(ref);
                            trailEntry.setProcessingStatus(
                                    ProcessingStatus.PROCESSED);
                            ctx.crawlSession()
                                    .getCrawlContext()
                                    .getCrawlEntryLedger()
                                    .updateEntry(trailEntry);
                        });

        //        var originalRef = ctx.docContext().getOriginalReference();
        //        var finalRef = ctx.docContext().getReference();
        //        if (StringUtils.isNotBlank(originalRef)
        //                && ObjectUtils.notEqual(originalRef, finalRef)) {
        //
        //            var originalDocRec = ctx.docContext().withReference(originalRef);
        //            originalDocRec.setOriginalReference(null);
        //            ctx.crawlContext()
        //                    .getCrawlEntryLedger()
        //                    .processed(originalDocRec);
        //        }
    }

}
