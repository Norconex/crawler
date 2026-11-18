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

import com.norconex.crawler.core.ledger.ProcessingOutcome;

import lombok.extern.slf4j.Slf4j;

@Slf4j
final class ProcessDelete {

    private ProcessDelete() {
    }

    static void execute(ProcessContext ctx) {
        LOG.debug("Deleting reference: {}", ctx.docContext().getReference());
        ctx.docContext().getCurrentCrawlEntry()
                .setProcessingOutcome(ProcessingOutcome.DELETED);
        // What was split off from, or embedded in, a document that is deleted
        // goes with it: nothing would ever produce it again. Not when the
        // document just went through the importer, and made its children
        // again: a parent that is rejected because it was split is deleted
        // by the bad state it ends in, and its new children replace the
        // previous ones, with the stale ones deleted after.
        if (!ctx.childrenHandled()) {
            ProcessChildren.deleteAll(ctx.crawlSession(),
                    ctx.docContext().getPreviousCrawlEntry());
        }
        // Event triggered by service
        ctx.crawlSession().getCrawlContext().getCommitterService()
                .delete(ctx.docContext().getDoc());
        ProcessFinalize.execute(ctx);
    }
}
