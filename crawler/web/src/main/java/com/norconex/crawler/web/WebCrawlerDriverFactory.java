/* Copyright 2024-2026 Norconex Inc.
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
package com.norconex.crawler.web;

import java.util.List;
import java.util.function.Supplier;

import com.norconex.crawler.core.CrawlerDriver;
import com.norconex.crawler.core.CrawlerDriver.FetchDriver;
import com.norconex.crawler.core.cmd.crawl.pipeline.bootstrap.ledger.CrawlerEntryLedgerBootstrapper;
import com.norconex.crawler.core.cmd.crawl.pipeline.bootstrap.queue.RefFileEnqueuer;
import com.norconex.crawler.core.cmd.crawl.pipeline.bootstrap.queue.RefListEnqueuer;
import com.norconex.crawler.core.cmd.crawl.pipeline.bootstrap.queue.RefProviderEnqueuer;
import com.norconex.crawler.web.callbacks.WebCrawlerCallbacks;
import com.norconex.crawler.web.doc.pipelines.WebDocPipelines;
import com.norconex.crawler.web.doc.pipelines.queue.SitemapEnqueuer;
import com.norconex.crawler.web.fetch.AggregatedWebFetchResponse;
import com.norconex.crawler.web.fetch.impl.httpclient.HttpClientFetchResponse;
import com.norconex.crawler.web.ledger.WebCrawlerEntry;

public class WebCrawlerDriverFactory implements Supplier<CrawlerDriver> {

    public static CrawlerDriver create() {
        return new WebCrawlerDriverFactory().get();
    }

    @Override
    public CrawlerDriver get() {
        return CrawlerDriver.builder()
                .fetchDriver(createFetchDriver())
                .bootstrappers(List.of(
                        new CrawlerEntryLedgerBootstrapper()))
                .referenceEnqueuers(List.of(
                        new SitemapEnqueuer(),
                        new RefListEnqueuer(),
                        new RefFileEnqueuer(),
                        new RefProviderEnqueuer()))
                .crawlerConfigClass(WebCrawlerConfig.class)
                .callbacks(WebCrawlerCallbacks.get())
                .docPipelines(WebDocPipelines.create())
                .crawlEntryType(WebCrawlerEntry.class)
                .build();
    }

    private static FetchDriver createFetchDriver() {
        return new FetchDriver()
                .responseAggregator(
                        (req, resps) -> new AggregatedWebFetchResponse(
                                resps))
                .unsuccesfulResponseFactory(
                        (state, msg, e) -> HttpClientFetchResponse
                                .builder()
                                .processingOutcome(
                                        state)
                                .reasonPhrase(msg)
                                .exception(e)
                                .statusCode(-1)
                                .build());
    }
}
