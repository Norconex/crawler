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

import org.apache.commons.lang3.StringUtils;

import com.norconex.crawler.core.CrawlerConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * Enqueues references from a list obtained from the crawler configuration
 * {@link CrawlerConfig#getStartReferences()}.
 */
@Slf4j
public class RefListEnqueuer implements ReferenceEnqueuer {

    @Override
    public int enqueue(QueueBootstrapContext ctx) {
        var cfg = ctx.getCrawlSession().getCrawlContext().getCrawlConfig();
        var cnt = 0;
        for (String ref : cfg.getStartReferences()) {
            if (ctx.isStopRequested()) {
                break;
            }
            if (StringUtils.isNotBlank(ref)) {
                ctx.queue(ref);
                cnt++;
            }
        }
        if (cnt > 0) {
            LOG.info("Queued {} start references from list.", cnt);
        }
        return cnt;
    }
}
