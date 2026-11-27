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
package com.norconex.crawler.fs.doc.pipelines.importer.stages;

import com.norconex.committer.core.acl.AclCodec;
import com.norconex.committer.core.acl.AclStatus;
import com.norconex.crawler.core.doc.pipelines.importer.ImporterPipelineContext;
import com.norconex.crawler.core.doc.pipelines.importer.stages.AbstractImporterStage;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.fetch.FetchDirective;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.fs.FsCrawlerConfig;
import com.norconex.crawler.fs.ledger.FsCrawlerEntry;

import lombok.NonNull;

/**
 * <p>
 * Rejects a file whose permissions were asked for but could not be read in
 * full, when {@link FsCrawlerConfig#isRejectIncompleteAcl()} is on, so it
 * goes no further than the fetch. Folders are not tested: they are never sent
 * anywhere.
 * </p>
 * <p>
 * Fires {@link CrawlerEvent#REJECTED_ACL} with what could not be read as its
 * message.
 * </p>
 */
public class AclStage extends AbstractImporterStage {

    public AclStage(@NonNull FetchDirective directive) {
        super(directive);
    }

    @Override
    protected boolean executeStage(ImporterPipelineContext ctx) {
        var session = ctx.getCrawlSession();
        if (!(session.getCrawlContext()
                .getCrawlConfig() instanceof FsCrawlerConfig config)
                || !config.isRejectIncompleteAcl()
                || !ctx.isFetchDirectiveEnabled(getFetchDirective())) {
            return true;
        }
        var entry = ctx.getDocContext().getCurrentCrawlEntry();
        if (entry instanceof FsCrawlerEntry fsEntry
                && fsEntry.isFolder() && !fsEntry.isFile()) {
            return true;
        }
        String why;
        try {
            var acl = AclCodec.read(ctx.getDocContext().getDoc().getMetadata())
                    .orElse(null);
            if (acl == null || acl.status() == AclStatus.COMPLETE) {
                return true;
            }
            why = acl.status() == AclStatus.NONE
                    ? "its permissions could not be read"
                    : "its permissions could only be read in part";
            if (!acl.unread().isEmpty()) {
                why += " (" + String.join("; ", acl.unread()) + ")";
            }
        } catch (IllegalArgumentException e) {
            why = "its permissions are not readable (" + e.getMessage() + ")";
        }
        entry.setProcessingOutcome(ProcessingOutcome.REJECTED);
        session.fire(CrawlerEvent.builder()
                .name(CrawlerEvent.REJECTED_ACL)
                .source(session)
                .crawlSession(session)
                .crawlEntry(entry)
                .message(why)
                .build());
        return false;
    }
}
