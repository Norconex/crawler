/* Copyright 2014-2026 Norconex Inc.
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
package com.norconex.crawler.core.doc.pipelines.committer.stages;

import java.util.function.Predicate;

import com.norconex.crawler.core.doc.pipelines.committer.CommitterPipelineContext;

/**
 * Common pipeline stage for committing documents.
 * <p>
 * A committer may withhold a document rather than send it (for example
 * because its access control list could not be read). Such a document must
 * be tried again on the next run, even if it does not change in the
 * meantime: its checksums are therefore not kept, so the next run sees it as
 * modified.
 * </p>
 */
public class CommitModuleStage implements Predicate<CommitterPipelineContext> {
    @Override
    public boolean test(CommitterPipelineContext ctx) {
        var entry = ctx.getDocContext().getCurrentCrawlEntry();
        // Event triggered by service
        ctx.getCrawlSession()
                .getCrawlContext()
                .getCommitterService()
                .upsert(ctx.getDocContext().getDoc(), (committer, reason) -> {
                    entry.setMetaChecksum(null);
                    entry.setContentChecksum(null);
                });

        //TODO rewind was done in CrawlerCommitterService when it was in this
        // core project. Now here... best place?
        ctx.getDocContext().getDoc().getInputStream().rewind();

        return true;
    }
}
