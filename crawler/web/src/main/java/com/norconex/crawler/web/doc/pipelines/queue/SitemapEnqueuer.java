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
package com.norconex.crawler.web.doc.pipelines.queue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.norconex.commons.lang.config.ConfigurationException;
import com.norconex.crawler.core.cmd.crawl.pipeline.bootstrap.queue.QueueBootstrapContext;
import com.norconex.crawler.core.cmd.crawl.pipeline.bootstrap.queue.ReferenceEnqueuer;
import com.norconex.crawler.web.doc.operations.sitemap.SitemapContext;
import com.norconex.crawler.web.ledger.WebCrawlerEntry;
import com.norconex.crawler.web.util.Web;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SitemapEnqueuer implements ReferenceEnqueuer {

    @Override
    public int enqueue(QueueBootstrapContext queueInitCtx) {

        var cfg = Web.config(queueInitCtx.getCrawlSession().getCrawlContext());
        var sitemapURLs = cfg.getStartReferencesSitemaps();
        var sitemapResolver = cfg.getSitemapResolver();

        if (!sitemapURLs.isEmpty() && sitemapResolver == null) {
            throw new ConfigurationException("""
                    One or more sitemap URLs were\s\
                    configured as start references but the sitemap resolver\s\
                    was set to null.
                    """);
        }

        final var urlCount = new AtomicInteger();
        Consumer<WebCrawlerEntry> urlConsumer = rec -> {
            queueInitCtx.queue(rec);
            urlCount.incrementAndGet();
        };

        // Process each sitemap URL
        for (String url : sitemapURLs) {
            if (queueInitCtx.isStopRequested()) {
                break;
            }
            sitemapResolver.resolve(SitemapContext.builder()
                    .fetcher(queueInitCtx.getCrawlSession().getCrawlContext()
                            .getFetcher())
                    .location(url)
                    .urlConsumer(urlConsumer)
                    .build());
        }
        if (urlCount.get() > 0) {
            LOG.info(
                    "Queued {} start references from {} sitemap(s).",
                    urlCount, sitemapURLs.size());
        }
        return urlCount.get();
    }
}
