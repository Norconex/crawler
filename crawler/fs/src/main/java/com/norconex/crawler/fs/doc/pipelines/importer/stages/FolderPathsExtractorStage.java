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
package com.norconex.crawler.fs.doc.pipelines.importer.stages;

import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.norconex.crawler.core.CrawlerConfig.ChangeDiscovery;
import com.norconex.crawler.core.CrawlerException;
import com.norconex.crawler.core.doc.pipelines.importer.ImporterPipelineContext;
import com.norconex.crawler.core.doc.pipelines.importer.stages.AbstractImporterStage;
import com.norconex.crawler.core.doc.pipelines.queue.QueuePipelineContext;
import com.norconex.crawler.core.fetch.FetchDirective;
import com.norconex.crawler.core.fetch.FetchException;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.fs.fetch.FolderPathsFetchRequest;
import com.norconex.crawler.fs.fetch.FolderPathsFetchResponse;
import com.norconex.crawler.fs.fetch.FsPath;
import com.norconex.crawler.fs.ledger.FsCrawlerEntry;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FolderPathsExtractorStage extends AbstractImporterStage {

    public FolderPathsExtractorStage(FetchDirective fetchDirective) {
        super(fetchDirective);
    }

    @Override
    protected boolean executeStage(ImporterPipelineContext ctx) {

        if (!ctx.isFetchDirectiveEnabled(getFetchDirective())
                || ctx.isMetadataDirectiveExecuted(getFetchDirective())) {
            return true;
        }

        var crawlSession = ctx.getCrawlSession();
        var crawlContext = crawlSession.getCrawlContext();
        var fetcher = crawlContext.getFetcher();

        var fsEntry =
                (FsCrawlerEntry) ctx.getDocContext().getCurrentCrawlEntry();
        if (fsEntry.isFolder()) {
            Set<FsPath> paths;
            FolderPathsFetchResponse resp;
            try {
                resp = (FolderPathsFetchResponse) fetcher
                        .fetch(new FolderPathsFetchRequest(
                                ctx.getDocContext().getDoc()));
                paths = resp.getChildPaths();
            } catch (FetchException e) {
                throw new CrawlerException("Could not fetch child paths of: "
                        + ctx.getDocContext().getReference(), e);
            }
            if (resp.getProcessingOutcome()
                    .isOneOf(ProcessingOutcome.NOT_FOUND)) {
                fsEntry.setProcessingOutcome(ProcessingOutcome.NOT_FOUND);
                queueKnownDescendantsForDeletion(ctx, fsEntry);
                return fsEntry.isFile();
            }
            for (FsPath fsPath : paths) {
                var newEntry = (FsCrawlerEntry) crawlContext
                        .createCrawlEntry(fsPath.getUri());
                newEntry.setDepth(fsEntry.getDepth() + 1);
                newEntry.setFile(fsPath.isFile());
                newEntry.setFolder(fsPath.isFolder());
                crawlContext
                        .getDocPipelines()
                        .getQueuePipeline()
                        .accept(new QueuePipelineContext(
                                crawlSession, newEntry));
            }
        }

        // On some file systems, a folder could also be a file, so we
        // continue if it is a file, regardless of folder logic above.
        return fsEntry.isFile();
    }

    private void queueKnownDescendantsForDeletion(
            ImporterPipelineContext ctx,
            FsCrawlerEntry missingFolder) {
        var crawlSession = ctx.getCrawlSession();
        var crawlContext = crawlSession.getCrawlContext();
        if (!crawlSession.isIncremental()
                || crawlContext.getCrawlConfig()
                        .getChangeDiscovery() != ChangeDiscovery.SOURCE_DELTA) {
            return;
        }

        var descendantPrefix = missingFolder.getReference() + "/items/";
        var queuedCount = 0;
        var ledger = crawlContext.getCrawlEntryLedger();
        var baselineEntries = new java.util.ArrayList<FsCrawlerEntry>();
        ledger.forEachBaseline(entry -> {
            if (entry instanceof FsCrawlerEntry fsBaseline
                    && StringUtils.startsWith(
                            fsBaseline.getReference(), descendantPrefix)) {
                baselineEntries.add(fsBaseline);
            }
        });

        for (FsCrawlerEntry fsBaseline : baselineEntries) {
            ledger.queue(fsBaseline);
            queuedCount++;
        }

        if (queuedCount > 0) {
            LOG.info("Queued {} known descendants for deletion after folder "
                    + "went missing in SOURCE_DELTA mode: {}",
                    queuedCount, missingFolder.getReference());
        }
    }
}
