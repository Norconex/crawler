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
package com.norconex.crawler.core.context;

import static java.util.Optional.ofNullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.committer.core.service.CommitterService;
import com.norconex.commons.lang.event.EventManager;
import com.norconex.commons.lang.io.CachedStreamFactory;
import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.CrawlerDriver;
import com.norconex.crawler.core.CrawlerException;
import com.norconex.crawler.core.doc.pipelines.DedupService;
import com.norconex.crawler.core.fetch.MultiFetcher;
import com.norconex.crawler.core.ledger.CrawlerEntryLedger;
import com.norconex.crawler.core.metrics.CrawlerMetricsImpl;
import com.norconex.crawler.core.util.ConfigUtil;
import com.norconex.crawler.core.util.ScopedThreadFactoryCreator;
import com.norconex.importer.Importer;
import com.norconex.importer.doc.Doc;

import lombok.AccessLevel;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class CrawlerContextFactory {

        @Data
        @Accessors(fluent = true)
        public static class Builder {
                private CrawlerDriver driver;
                private CrawlerConfig config;

                public CrawlerContextFactory build() {
                        return new CrawlerContextFactory(driver, config);
                }
        }

        public static Builder builder() {
                return new Builder();
        }

        private final CrawlerDriver driver;
        private final CrawlerConfig config;

        public CrawlerContext create() {
                var workDir = ConfigUtil.resolveWorkDir(config);
                var eventManager = ofNullable(driver.eventManager())
                                .orElse(new EventManager());
                var tempDir = ConfigUtil.resolveTempDir(config);
                createDir(tempDir); // Will also create "workdir", temp's parent

                return CrawlerContext.builder()
                                .beanMapper(driver.beanMapper())
                                .bootstrappers(driver.bootstrappers())
                                .callbacks(driver.callbacks())
                                .committerService(CommitterService
                                                .<Doc>builder()
                                                .committers(config
                                                                .getCommitters())
                                                .eventManager(eventManager)
                                                .upsertRequestBuilder(
                                                                doc -> new UpsertRequest(
                                                                                doc.getReference(),
                                                                                doc.getMetadata(),
                                                                                // InputStream closed by caller
                                                                                doc.getInputStream()))
                                                .deleteRequestBuilder(
                                                                doc -> new DeleteRequest(
                                                                                doc.getReference(),
                                                                                doc.getMetadata()))
                                                .build())
                                .crawlConfig(config)
                                .dedupService(new DedupService())
                                .crawlEntryType(driver.crawlEntryType())
                                .cacheTypes(driver.cacheTypes())
                                .docPipelines(driver.docPipelines())
                                .crawlEntryLedger(new CrawlerEntryLedger())
                                .eventManager(eventManager)
                                .fetcher(MultiFetcher.builder()
                                                .fetchers(config.getFetchers())
                                                .maxRetries(config
                                                                .getFetchersMaxRetries())
                                                .retryDelay(config
                                                                .getFetchersRetryDelay())
                                                .responseAggregator(
                                                                driver.fetchDriver()
                                                                                .responseAggregator())
                                                .unsuccessfulResponseFactory(
                                                                driver.fetchDriver()
                                                                                .unsuccesfulResponseFactory())
                                                .build())
                                .metrics(new CrawlerMetricsImpl())
                                .importer(new Importer(
                                                config.getImporterConfig(),
                                                eventManager))
                                .streamFactory(new CachedStreamFactory(
                                                (int) config.getMaxStreamCachePoolSize(),
                                                (int) config.getMaxStreamCacheSize(),
                                                tempDir))
                                .tempDir(tempDir)
                                .threadFactoryCreator(
                                                new ScopedThreadFactoryCreator(
                                                                "crawl"))
                                .workDir(workDir)
                                .build();
        }

        private void createDir(Path dir) {
                try {
                        Files.createDirectories(dir);
                } catch (IOException e) {
                        throw new CrawlerException(
                                        "Could not create directory: " + dir,
                                        e);
                }
        }
}
