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
package com.norconex.crawler.core.cmd.crawl.pipeline.bootstrap.queue;

import com.norconex.crawler.core.cluster.pipeline.BaseStep;
import com.norconex.crawler.core.session.CrawlerSession;

/**
 * Queues the start references, from the reference enqueuers the crawler
 * driver provides. This is the only place start references are queued.
 */
public class StartRefsQueueStep extends BaseStep {
    public StartRefsQueueStep(String id) {
        super(id);
    }

    @Override
    public void execute(CrawlerSession session) {
        var enqueuers = session.getCrawlContext().getReferenceEnqueuers();
        // A context built without enqueuers (e.g., in tests) gets the
        // default ones.
        var bootstrapper = enqueuers.isEmpty()
                ? new QueueBootstrapper()
                : new QueueBootstrapper(enqueuers);
        bootstrapper.bootstrap(session);
    }
}
