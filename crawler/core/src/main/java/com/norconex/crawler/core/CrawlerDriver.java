/* Copyright 2025-2026 Norconex Inc.
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
package com.norconex.crawler.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.event.EventManager;
import com.norconex.crawler.core.cmd.crawl.pipeline.CrawlerPipelineFactory;
import com.norconex.crawler.core.cmd.crawl.pipeline.DefaultCrawlPipelineFactory;
import com.norconex.crawler.core.cmd.crawl.pipeline.bootstrap.CrawlerBootstrapper;
import com.norconex.crawler.core.cmd.crawl.pipeline.bootstrap.ledger.CrawlerEntryLedgerBootstrapper;
import com.norconex.crawler.core.cmd.crawl.pipeline.bootstrap.queue.RefFileEnqueuer;
import com.norconex.crawler.core.cmd.crawl.pipeline.bootstrap.queue.RefListEnqueuer;
import com.norconex.crawler.core.cmd.crawl.pipeline.bootstrap.queue.RefProviderEnqueuer;
import com.norconex.crawler.core.cmd.crawl.pipeline.bootstrap.queue.ReferenceEnqueuer;
import com.norconex.crawler.core.doc.pipelines.CrawlerDocPipelines;
import com.norconex.crawler.core.fetch.MultiFetcher.ResponseAggregator;
import com.norconex.crawler.core.fetch.MultiFetcher.UnsuccessfulResponseFactory;
import com.norconex.crawler.core.ledger.CrawlerEntry;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

@Builder(builderClassName = "Builder")
@Getter
@Accessors(fluent = true)
@NonNull
public class CrawlerDriver {

    @Default
    private final CrawlerPipelineFactory crawlPipelineFactory =
            new DefaultCrawlPipelineFactory();

    @Default
    private final Class<? extends CrawlerConfig> crawlerConfigClass =
            CrawlerConfig.class;

    @Default
    private final List<CrawlerBootstrapper> bootstrappers =
            new ArrayList<>(List.of(
                    new CrawlerEntryLedgerBootstrapper()));

    /**
     * Sources of start references. They are queued once per crawl, by the
     * crawl pipeline (not by a bootstrapper). Crawlers with their own kinds
     * of start references (e.g., sitemaps) provide them here, in addition to
     * the defaults: references listed in the configuration, read from files,
     * and obtained from providers.
     */
    @Default
    private final List<ReferenceEnqueuer> referenceEnqueuers =
            defaultReferenceEnqueuers();

    /**
     * Gets a new list of the default reference enqueuers: configured
     * references, reference files and reference providers.
     * @return default reference enqueuers
     */
    public static List<ReferenceEnqueuer> defaultReferenceEnqueuers() {
        return new ArrayList<>(List.of(
                new RefListEnqueuer(),
                new RefFileEnqueuer(),
                new RefProviderEnqueuer()));
    }

    private final CrawlerDocPipelines docPipelines;
    @Default
    private final CrawlerCallbacks callbacks =
            CrawlerCallbacks.builder().build();
    @Default
    private final BeanMapper beanMapper = BeanMapper.DEFAULT;
    @Default
    private final EventManager eventManager = new EventManager();
    /**
     * Provides a required fetcher implementation, responsible for obtaining
     * resources being crawled.
     */
    private final FetchDriver fetchDriver;

    /**
     * The exact type of {@link CrawlerEntry} if your crawler is subclassing
     * it. Defaults to {@link CrawlerEntry} class.
     */
    @Default
    private final Class<? extends CrawlerEntry> crawlEntryType =
            CrawlerEntry.class;

    /**
     * Optional map of Hazelcast map-config-name (or wildcard pattern as
     * declared in the YAML, e.g. {@code "ledger_*"}) to the concrete Java
     * type that should be (de)serialized for values in that map.
     * The framework automatically adds the {@code ledger_*} mapping using
     * {@link #crawlEntryType}. Extensions that persist their own types should
     * add their entries here.
     */
    @Default
    private final Map<String, Class<?>> cacheTypes = new HashMap<>();

    @Accessors(fluent = true)
    @Data
    @NonNull
    public static class FetchDriver {
        private UnsuccessfulResponseFactory unsuccesfulResponseFactory;
        private ResponseAggregator responseAggregator;
    }
}
