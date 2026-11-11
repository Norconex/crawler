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
package com.norconex.crawler.core.cmd.crawl.pipeline.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.cmd.crawl.pipeline.process.CrawlerProcessStep.ProcessQueueAction;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.junit.WithLogLevel;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.core.util.ConcurrentUtil;
import com.norconex.crawler.core.util.ScopedThreadFactoryCreator;

/**
 * The crawl threads of a processing step end with the step, also when one
 * of them failed. Left parked, they would keep the JVM from exiting.
 */
@Timeout(30)
@WithLogLevel(value = "OFF", classes = CrawlerProcessStep.class) // tests expect exceptions
class CrawlerProcessStepThreadsTest {

    private static final int NUM_THREADS = 3;

    @Test
    void testThreadsEndWhenATaskFails() {
        var crawlerId = "threads-test-" + UUID.randomUUID();
        var cfg = new CrawlerConfig().setNumThreads(NUM_THREADS);
        var ctx = mock(CrawlerContext.class);
        when(ctx.getCrawlConfig()).thenReturn(cfg);
        when(ctx.getThreadFactoryCreator())
                .thenReturn(new ScopedThreadFactoryCreator("scope"));
        var session = mock(CrawlerSession.class);
        when(session.getCrawlContext()).thenReturn(ctx);
        when(session.getCrawlerId()).thenReturn(crawlerId);

        var step = new CrawlerProcessStep(
                "process", ProcessQueueAction.CRAWL_ALL) {
            @Override
            void processQueue(CrawlerSession s, int threadIndex) {
                if (threadIndex == 0) {
                    throw new IllegalStateException("Simulated failure.");
                }
            }
        };

        assertThatThrownBy(() -> step.execute(session))
                .hasRootCauseMessage("Simulated failure.");

        // Shut-down pool threads end promptly; this only waits for that.
        assertThat(ConcurrentUtil.waitUntil(
                () -> crawlThreads(crawlerId).isEmpty(),
                Duration.ofSeconds(10)))
                        .as("crawl threads still alive: %s",
                                crawlThreads(crawlerId))
                        .isTrue();
    }

    private static List<String> crawlThreads(String crawlerId) {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> name.contains(crawlerId))
                .toList();
    }
}
