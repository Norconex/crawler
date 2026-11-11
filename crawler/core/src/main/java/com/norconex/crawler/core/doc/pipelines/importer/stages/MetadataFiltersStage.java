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
package com.norconex.crawler.core.doc.pipelines.importer.stages;

import java.util.List;

import com.norconex.commons.lang.map.Properties;
import com.norconex.crawler.core.doc.operations.filter.MetadataFilter;
import com.norconex.crawler.core.doc.pipelines.OnMatchFiltersResolver;
import com.norconex.crawler.core.doc.pipelines.importer.ImporterPipelineContext;
import com.norconex.crawler.core.fetch.FetchDirective;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.core.event.CrawlerEvent;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Filters documents based on fetched metadata. These filters are invoked
 * only once (even if both metadata and document directives are enabled).
 */
@Slf4j
public class MetadataFiltersStage extends AbstractImporterStage {

    public MetadataFiltersStage(@NonNull FetchDirective fetchDirective) {
        super(fetchDirective);
    }

    /**
     * The metadata filters to apply to the document being processed. Defaults
     * to the configured metadata filters. Crawlers dealing with different
     * kinds of entries can override this to apply different ones.
     * @param ctx importer pipeline context
     * @return metadata filters (never {@code null})
     */
    protected List<MetadataFilter> getFilters(ImporterPipelineContext ctx) {
        return ctx.getCrawlSession().getCrawlContext().getCrawlConfig()
                .getMetadataFilters();
    }

    /**
     * Whether the document being processed is accepted without being tested.
     * Defaults to {@code false}.
     * @param ctx importer pipeline context
     * @return {@code true} to accept the document without applying any filter
     */
    protected boolean isExempt(ImporterPipelineContext ctx) {
        return false;
    }

    @Override
    protected boolean executeStage(ImporterPipelineContext ctx) {

        if (!ctx.isFetchDirectiveEnabled(getFetchDirective())
                || ctx.isMetadataDirectiveExecuted(getFetchDirective())
                || isExempt(ctx)) {
            return true;
        }

        var doc = ctx.getDocContext().getDoc();

        return OnMatchFiltersResolver
                .<Properties, MetadataFilter>builder()
                .subject(doc.getMetadata())
                .filters(getFilters(ctx))
                .predicate((s, f) -> f
                        .acceptMetadata(doc.getReference(), s))
                .onRejected((f, msg) -> {
                    LOG.debug("REJECTED metadata. Reference: {} Filter={}",
                            doc.getReference(), f);
                    ctx.getCrawlSession().fire(CrawlerEvent
                            .builder()
                            .name(CrawlerEvent.REJECTED_FILTER)
                            .crawlSession(ctx.getCrawlSession())
                            .crawlEntry(
                                    ctx.getDocContext().getCurrentCrawlEntry())
                            .source(f)
                            .message(msg)
                            .build());
                    ctx.getDocContext().getCurrentCrawlEntry()
                            .setProcessingOutcome(ProcessingOutcome.REJECTED);
                })
                .build()
                .isAccepted();
    }
}
