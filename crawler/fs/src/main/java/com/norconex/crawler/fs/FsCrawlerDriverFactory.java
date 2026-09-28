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
package com.norconex.crawler.fs;

import java.util.function.Supplier;

import com.norconex.crawler.core.CrawlerCallbacks;
import com.norconex.crawler.core.CrawlerDriver;
import com.norconex.crawler.core.CrawlerDriver.FetchDriver;
import com.norconex.crawler.fs.callbacks.BeforeFsCommand;
import com.norconex.crawler.fs.doc.pipelines.FsPipelines;
import com.norconex.crawler.fs.fetch.AggregatedFileFetchResponse;
import com.norconex.crawler.fs.fetch.AggregatedFolderPathsResponse;
import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.crawler.fs.fetch.impl.GenericFileFetchResponse;
import com.norconex.crawler.fs.ledger.FsCrawlerEntry;

public class FsCrawlerDriverFactory implements Supplier<CrawlerDriver> {

    public static CrawlerDriver create() {
        return new FsCrawlerDriverFactory().get();
    }

    @Override
    public CrawlerDriver get() {
        return CrawlerDriver.builder()
                .fetchDriver(createFetchDriver())
                .crawlerConfigClass(FsCrawlerConfig.class)
                .callbacks(CrawlerCallbacks.builder()
                        .beforeCommand(new BeforeFsCommand())
                        .build())
                .docPipelines(FsPipelines.create())
                .crawlEntryType(FsCrawlerEntry.class)
                .build();
    }

    private static FetchDriver createFetchDriver() {
        return new FetchDriver()
                .responseAggregator(
                        (req, resps) -> (req instanceof FileFetchRequest
                                ? new AggregatedFileFetchResponse(
                                        resps)
                                : new AggregatedFolderPathsResponse(
                                        resps)))
                .unsuccesfulResponseFactory(
                        (state, msg, e) -> GenericFileFetchResponse
                                .builder()
                                .processingOutcome(
                                        state)
                                .reasonPhrase(msg)
                                .exception(e)
                                .build());
    }
}
