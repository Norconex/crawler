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
package com.norconex.crawler.core.doc.pipelines.queue.stages;

import java.util.List;
import java.util.function.Predicate;

import org.apache.commons.lang3.StringUtils;

import com.norconex.crawler.core.doc.operations.filter.ReferenceFilter;
import com.norconex.crawler.core.doc.pipelines.OnMatchFiltersResolver;
import com.norconex.crawler.core.doc.pipelines.queue.QueuePipelineContext;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.ledger.ProcessingOutcome;

import lombok.extern.slf4j.Slf4j;

/**
 * Common pipeline stage for filtering references.
 */
@Slf4j
public class ReferenceFiltersStage implements Predicate<QueuePipelineContext> {

    private final String type;

    public ReferenceFiltersStage() {
        this(null);
    }

    public ReferenceFiltersStage(String type) {
        this.type = type;
    }

    /**
     * The reference filters to apply to the entry being queued. Defaults to
     * the configured reference filters. Crawlers dealing with different kinds
     * of entries can override this to apply different ones.
     * @param ctx queue pipeline context
     * @return reference filters (never {@code null})
     */
    protected List<ReferenceFilter> getFilters(QueuePipelineContext ctx) {
        return ctx.getCrawlSession().getCrawlContext().getCrawlConfig()
                .getReferenceFilters();
    }

    /**
     * Whether the entry being queued is accepted without being tested.
     * Defaults to {@code false}.
     * @param ctx queue pipeline context
     * @return {@code true} to accept the entry without applying any filter
     */
    protected boolean isExempt(QueuePipelineContext ctx) {
        return false;
    }

    @Override
    public boolean test(QueuePipelineContext ctx) {
        if (isExempt(ctx)) {
            return true;
        }
        var msgSuffix = StringUtils.isBlank(type)
                ? ""
                : " (" + StringUtils.trimToEmpty(type) + ")";

        var crawlEntry = ctx.getCrawlEntry();
        return OnMatchFiltersResolver
                .<String, ReferenceFilter>builder()
                .subject(crawlEntry.getReference())
                .filters(getFilters(ctx))
                .predicate((s, f) -> f.acceptReference(s))
                .onRejected((f, msg) -> {
                    LOG.debug("REJECTED reference{}: {} Filter={}",
                            msgSuffix, crawlEntry.getReference(), f);
                    ctx.getCrawlSession().fire(
                            CrawlerEvent.builder()
                                    .name(CrawlerEvent.REJECTED_FILTER)
                                    .crawlSession(ctx.getCrawlSession())
                                    .source(f)
                                    .message(msg + msgSuffix)
                                    .build());
                    crawlEntry.setProcessingOutcome(ProcessingOutcome.REJECTED);
                })
                .build()
                .isAccepted();
    }
}
