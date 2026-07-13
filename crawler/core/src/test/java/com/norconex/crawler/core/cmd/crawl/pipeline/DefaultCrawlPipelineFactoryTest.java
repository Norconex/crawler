/* Copyright 2026 Norconex Inc.
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
package com.norconex.crawler.core.cmd.crawl.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.session.CrawlerSession;

class DefaultCrawlPipelineFactoryTest {

    private final DefaultCrawlPipelineFactory factory =
            new DefaultCrawlPipelineFactory();

    @Test
    void testIncrementalCrawlerScanKeepsOrphanSteps() {
        var session = mockSession(
                true,
                CrawlerConfig.OrphansStrategy.PROCESS,
                CrawlerConfig.ChangeDiscovery.CRAWLER_SCAN);

        var pipeline = factory.create(session);

        assertThat(pipeline.getSteps().keySet())
                .contains(
                        DefaultCrawlPipelineFactory.STEP_CRAWL_ORPHANS,
                        DefaultCrawlPipelineFactory.STEP_CRAWL_DOCUMENTS,
                        DefaultCrawlPipelineFactory.STEP_INITIAL_QUEUE);
    }

    @Test
    void testIncrementalSourceDeltaSkipsOrphanSteps() {
        var session = mockSession(
                true,
                CrawlerConfig.OrphansStrategy.PROCESS,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA);

        var pipeline = factory.create(session);

        assertThat(pipeline.getSteps().keySet())
                .containsExactly(
                        DefaultCrawlPipelineFactory.STEP_INITIAL_QUEUE,
                        DefaultCrawlPipelineFactory.STEP_CRAWL_DOCUMENTS);
    }

    @Test
    void testFullSourceDeltaDoesNotSuppressOrphanSteps() {
        var session = mockSession(
                false,
                CrawlerConfig.OrphansStrategy.DELETE,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA);

        var pipeline = factory.create(session);

        assertThat(pipeline.getSteps().keySet())
                .contains(DefaultCrawlPipelineFactory.STEP_DELETE_ORPHANS);
    }

    private CrawlerSession mockSession(
            boolean incremental,
            CrawlerConfig.OrphansStrategy orphansStrategy,
            CrawlerConfig.ChangeDiscovery changeDiscovery) {
        var config = new CrawlerConfig()
                .setOrphansStrategy(orphansStrategy)
                .setChangeDiscovery(changeDiscovery);
        var context = mock(CrawlerContext.class);
        when(context.getCrawlConfig()).thenReturn(config);

        var session = mock(CrawlerSession.class);
        when(session.getCrawlContext()).thenReturn(context);
        when(session.isIncremental()).thenReturn(incremental);
        return session;
    }
}
