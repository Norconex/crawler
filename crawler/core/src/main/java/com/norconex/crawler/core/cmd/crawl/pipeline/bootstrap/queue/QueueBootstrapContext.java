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
package com.norconex.crawler.core.cmd.crawl.pipeline.bootstrap.queue;

import java.util.function.Consumer;

import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.session.CrawlerSession;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

/**
 * Holds contextual objects necessary to initialize a crawler queue.
 */
@Accessors(fluent = false)
@AllArgsConstructor
public class QueueBootstrapContext {
    @Getter
    private final CrawlerSession crawlSession;
    private final Consumer<CrawlerEntry> queuer;

    public void queue(@NonNull String reference) {
        var rec = crawlSession.getCrawlContext().createCrawlEntry(reference);
        rec.setDepth(0);
        queue(rec);
    }

    public void queue(@NonNull CrawlerEntry entry) {
        queuer.accept(entry);
    }

    /**
     * Whether the crawler is being stopped. Enqueuers check it between
     * references and stop queueing when it is {@code true}. Queueing is
     * then left unfinished, and resumes with the crawl.
     * @return {@code true} if a stop was requested
     */
    public boolean isStopRequested() {
        return crawlSession.isStopRequested();
    }
}
