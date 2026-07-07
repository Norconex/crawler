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

import com.norconex.crawler.core.doc.CrawlerDocContext;
import com.norconex.crawler.core.doc.pipelines.committer.CommitterPipelineContext;
import com.norconex.crawler.core.doc.pipelines.importer.ImporterPipelineContext;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.importer.response.ImporterResponse;

import lombok.extern.slf4j.Slf4j;

@Slf4j
final class ProcessUpsert {

    private ProcessUpsert() {
    }

    //TODO see how much of this can be migrated to a doc-specific pipeline
    // and eliminate this class (or most of it)?
    // .. same with Delete/Finalize variants

    static void execute(ProcessContext ctx) {
        if (importDocument(ctx)) {
            processImportResponse(ctx);
        }
    }

    private static boolean importDocument(ProcessContext ctx) {
        // The importer pipeline also takes care of fetching
        //TODO shall fetching be handled by core, and we just pass
        // fetched doc to importer pipeline?
        var crawlCtx = ctx.crawlSession().getCrawlContext();
        var currentEntry = ctx.docContext().getCurrentCrawlEntry();

        LOG.debug("Processing reference: {}", currentEntry.getReference());

        var response = crawlCtx
                .getDocPipelines()
                .getImporterPipeline()
                .apply(new ImporterPipelineContext(
                        ctx.crawlSession(), ctx.docContext()));
        ctx.importerResponse(response);

        // no response means rejected even if it should not be the
        // way to do it
        if (response == null) {
            if ((currentEntry.getProcessingOutcome() != null)
                    && currentEntry.getProcessingOutcome().isNewOrModified()) {
                currentEntry.setProcessingOutcome(ProcessingOutcome.REJECTED);
            }
            ProcessFinalize.execute(ctx);
            return false;
        }
        return true;
    }

    // commit is upsert this method is recursively invoked for children
    private static void processImportResponse(ProcessContext ctx) {

        if (!commitOrRejectDocument(ctx)) {
            ProcessFinalize.execute(ctx);
            return;
        }

        // Finalize (i.e., write to the crawl ledger) on the success path
        // too, not just on rejection. Without this, a successfully
        // committed document is never marked processed in the ledger:
        // for the top-level document this is harmless (CrawlerProcessStep
        // finalizes it afterward regardless), but for a child/embedded
        // document produced by the importer (e.g. a zip entry or email
        // attachment) this method is the ONLY place it is ever touched,
        // so skipping it means the child can never be detected as an
        // orphan (and deleted from the target repository) on a later
        // crawl once it disappears from its parent. ProcessFinalize is
        // idempotent (guarded by ctx.finalized()), so finalizing here is
        // safe even though the top-level context also gets finalized
        // again afterward by its caller.
        ProcessFinalize.execute(ctx);

        var children = ctx.importerResponse().getNestedResponses();
        for (ImporterResponse childResponse : children) {

            //TODO have a createEmbeddedDoc method instead?
            // TODO have a docInfoFactory instead and arguments
            // dictate whether it is a child, embedded, or top level
            var childCurrentEntry = ctx
                    .crawlSession()
                    .getCrawlContext()
                    .createCrawlEntry(childResponse.getReference());
            //            childDocRec.setReference(childResponse.getReference());
            var childPreviousEntry = ctx
                    .crawlSession()
                    .getCrawlContext()
                    .getCrawlEntryLedger()
                    .getBaselineEntry(childResponse.getReference())
                    .orElse(null);

            // Children never go through a fetch/queue stage (there's no
            // fetcher for an embedded doc), so nothing else ever sets an
            // outcome on them. Without this, ProcessFinalize would treat
            // a freshly-committed child as having an "unknown" outcome
            // and mistakenly run it through bad-state/deletion handling.
            childCurrentEntry.setProcessingOutcome(
                    childPreviousEntry == null
                            ? ProcessingOutcome.NEW
                            : ProcessingOutcome.MODIFIED);

            // Here we create a CrawlDoc since the document from the response
            // is (or can be) just a Doc, which does not hold all required
            // properties for crawling.
            //TODO refactor Doc vs CrawlDoc to have only one instance
            // so we do not have to create such copy?
            var childResponseDoc = childResponse.getDoc();
            var childDocContext = CrawlerDocContext
                    .builder()
                    .currentCrawlEntry(childCurrentEntry)
                    .previousCrawlEntry(childPreviousEntry)
                    .doc(childResponseDoc)
                    .build();
            //            if (childResponseDoc != null) {
            //                childDocContext.getOtherProps().putAll(
            //                        childResponseDoc.getMetadata());
            //            }

            var childCtx = new ProcessContext()
                    .crawlSession(ctx.crawlSession())
                    //                    .orphan(ctx.orphan())
                    .docContext(childDocContext)
                    .importerResponse(childResponse);

            processImportResponse(childCtx);
        }
    }

    private static boolean commitOrRejectDocument(ProcessContext ctx) {
        var session = ctx.crawlSession();
        var crawlCtx = session.getCrawlContext();
        var currentEntry = ctx.docContext().getCurrentCrawlEntry();
        var response = ctx.importerResponse();

        // ok, there's a resonse, but is it good?
        var msg = response.toString();
        if (!response.getNestedResponses().isEmpty()) {
            msg += "(" + response.getNestedResponses().size()
                    + " nested responses.)";
        }

        if (response.isSuccess()) {
            session.fire(CrawlerEvent.builder()
                    .name(CrawlerEvent.DOCUMENT_IMPORTED)
                    .crawlSession(ctx.crawlSession())
                    .crawlEntry(currentEntry)
                    .source(response)
                    .message(msg)
                    .build());
            crawlCtx.getDocPipelines()
                    .getCommitterPipeline()
                    .accept(new CommitterPipelineContext(ctx.crawlSession(),
                            ctx.docContext()));
            return true;
        }

        currentEntry.setProcessingOutcome(ProcessingOutcome.REJECTED);
        session.fire(CrawlerEvent.builder()
                .name(CrawlerEvent.REJECTED_IMPORT)
                .crawlSession(ctx.crawlSession())
                .crawlEntry(currentEntry)
                .source(response)
                .message(msg)
                .build());
        LOG.debug(
                "Importing unsuccessful for \"{}\": {}",
                currentEntry.getReference(),
                response.getDescription());

        return false;

        //TODO Fire an event here? If we get here, the importer did
        //not kick in,
        //so do not fire REJECTED_IMPORT (like it used to).
        //Errors should have fired
        //something already so do not fire two REJECTED... but
        //what if a previous issue did not fire a REJECTED_*?
        //This should not happen, but keep an eye on that.
        //OR do we want to always fire REJECTED_IMPORT on import failure
        //(in addition to whatever) and maybe a new REJECTED_COLLECTOR
        //when it did not reach the importer module?

    }
}
