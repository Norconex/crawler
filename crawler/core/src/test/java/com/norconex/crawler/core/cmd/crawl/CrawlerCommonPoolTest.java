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
package com.norconex.crawler.core.cmd.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.commons.lang.event.Event;
import com.norconex.crawler.core.Crawler;
import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.mocks.fetch.MockFetcher;
import com.norconex.crawler.core.test.CrawlerTestDriver;

/**
 * A running crawl does not hold threads of the JVM-wide common fork-join
 * pool for its duration. That pool is small (one less than the CPU count)
 * and shared: Hazelcast, for one, completes its asynchronous map operations
 * there, so a few crawls holding its threads could stall every such
 * operation in the JVM.
 */
@Timeout(60)
class CrawlerCommonPoolTest {

    @TempDir
    private Path tempDir;

    @Test
    void testCrawlLeavesCommonPoolFree() {
        var checked = new AtomicBoolean();
        var commonPoolHolders = new CopyOnWriteArrayList<String>();
        var config = new CrawlerConfig()
                .setId("common-pool-test")
                .setWorkDir(tempDir)
                .setStartReferences(List.of("ref-1"))
                .setFetchers(List.of(Configurable.configure(
                        new MockFetcher(),
                        cfg -> cfg.setDelay(Duration.ofMillis(10)))))
                .setNumThreads(1)
                .setIdleTimeout(Duration.ofMillis(500));
        // Mid-crawl, with every crawl component started.
        config.addEventListener((Event event) -> {
            if (event.is(CrawlerEvent.DOCUMENT_IMPORTED)
                    && !checked.getAndSet(true)) {
                commonPoolHolders.addAll(crawlCodeInCommonPool());
            }
        });

        new Crawler(CrawlerTestDriver.create(), config).crawl();

        assertThat(checked).as("the check ran during the crawl").isTrue();
        assertThat(commonPoolHolders)
                .as("common-pool threads running crawler code")
                .isEmpty();
    }

    // Ignores test classes, which other tests running in parallel may
    // briefly put on the common pool.
    private static List<String> crawlCodeInCommonPool() {
        return Thread.getAllStackTraces().entrySet().stream()
                .filter(e -> e.getKey().getName()
                        .startsWith("ForkJoinPool.commonPool"))
                .filter(e -> Arrays.stream(e.getValue())
                        .map(StackTraceElement::getClassName)
                        .anyMatch(cls -> cls.startsWith("com.norconex.crawler.")
                                && !cls.matches(".*Test(\\$.*)?")))
                .map(Map.Entry::getKey)
                .map(Thread::getName)
                .toList();
    }
}
