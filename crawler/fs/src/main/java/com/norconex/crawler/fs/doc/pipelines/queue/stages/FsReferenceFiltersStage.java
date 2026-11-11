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
package com.norconex.crawler.fs.doc.pipelines.queue.stages;

import java.util.List;

import com.norconex.crawler.core.doc.operations.filter.ReferenceFilter;
import com.norconex.crawler.core.doc.pipelines.queue.QueuePipelineContext;
import com.norconex.crawler.core.doc.pipelines.queue.stages.ReferenceFiltersStage;
import com.norconex.crawler.fs.FsCrawlerConfig;
import com.norconex.crawler.fs.ledger.FsCrawlerEntry;

/**
 * Filters references on a file system: start references are never filtered,
 * a folder is tested by the folder reference filters, and anything else by
 * the reference filters.
 * @see FsCrawlerConfig
 */
public class FsReferenceFiltersStage extends ReferenceFiltersStage {

    /**
     * Start references are the only ones queued at depth zero.
     */
    @Override
    protected boolean isExempt(QueuePipelineContext ctx) {
        return ctx.getCrawlEntry().getDepth() == 0;
    }

    @Override
    protected List<ReferenceFilter> getFilters(QueuePipelineContext ctx) {
        var config = ctx.getCrawlSession().getCrawlContext().getCrawlConfig();
        if (isFolderOnly(ctx.getCrawlEntry())) {
            return config instanceof FsCrawlerConfig fsConfig
                    ? fsConfig.getFolderReferenceFilters()
                    : List.of();
        }
        return config.getReferenceFilters();
    }

    /**
     * Whether an entry is a folder and nothing else. Some file systems have
     * entries that are both a file and a folder: those are files, too.
     */
    static boolean isFolderOnly(Object entry) {
        return entry instanceof FsCrawlerEntry fsEntry
                && fsEntry.isFolder()
                && !fsEntry.isFile();
    }
}
