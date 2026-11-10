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

import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.doc.pipelines.queue.ReferencesProvider;

import lombok.extern.slf4j.Slf4j;

/**
 * Enqueues references from files obtained from the crawler configuration
 * {@link CrawlerConfig#getStartReferencesProviders()}.
 */
@Slf4j
public class RefProviderEnqueuer implements ReferenceEnqueuer {

    @Override
    public int enqueue(QueueBootstrapContext ctx) {
        var cfg = ctx.getCrawlSession().getCrawlContext().getCrawlConfig();
        var providers = cfg.getStartReferencesProviders();
        var cnt = 0;
        for (ReferencesProvider provider : providers) {
            if (ctx.isStopRequested()) {
                break;
            }
            if (provider == null) {
                continue;
            }
            var it = provider.provideReferences();
            while (it.hasNext() && !ctx.isStopRequested()) {
                ctx.queue(it.next());
                cnt++;
            }
        }
        if (cnt > 0) {
            LOG.info("Queued {} start references from {} providers.",
                    cnt, providers.size());
        }
        return cnt;
    }
}
