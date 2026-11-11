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

import java.util.List;

import com.norconex.crawler.core.doc.operations.filter.MetadataFilter;
import com.norconex.crawler.core.doc.pipelines.importer.ImporterPipelineContext;
import com.norconex.crawler.core.doc.pipelines.importer.stages.MetadataFiltersStage;
import com.norconex.crawler.core.fetch.FetchDirective;
import com.norconex.crawler.fs.FsCrawlerConfig;
import com.norconex.crawler.fs.ledger.FsCrawlerEntry;

import lombok.NonNull;

/**
 * Filters metadata on a file system: start references are never filtered,
 * a folder is tested by the folder metadata filters, and anything else by
 * the metadata filters.
 * @see FsCrawlerConfig
 */
public class FsMetadataFiltersStage extends MetadataFiltersStage {

    public FsMetadataFiltersStage(@NonNull FetchDirective fetchDirective) {
        super(fetchDirective);
    }

    /**
     * Start references are the only ones queued at depth zero.
     */
    @Override
    protected boolean isExempt(ImporterPipelineContext ctx) {
        return ctx.getDocContext().getCurrentCrawlEntry().getDepth() == 0;
    }

    @Override
    protected List<MetadataFilter> getFilters(ImporterPipelineContext ctx) {
        var config = ctx.getCrawlSession().getCrawlContext().getCrawlConfig();
        var entry = ctx.getDocContext().getCurrentCrawlEntry();
        // Some file systems have entries that are both a file and a folder:
        // those are files, too.
        if (entry instanceof FsCrawlerEntry fsEntry
                && fsEntry.isFolder() && !fsEntry.isFile()) {
            return config instanceof FsCrawlerConfig fsConfig
                    ? fsConfig.getFolderMetadataFilters()
                    : List.of();
        }
        return config.getMetadataFilters();
    }
}
