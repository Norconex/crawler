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
package com.norconex.crawler.web.cases.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.crawler.core.junit.WithLogLevel;
import com.norconex.crawler.core.test.CrawlerTestHarness;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.test.CrawlerTestHarness;
import com.norconex.crawler.core.test.CrawlerTestInstrument;
import com.norconex.crawler.web.WebCrawlerDriverFactory;

import lombok.extern.slf4j.Slf4j;

/**
 * Test that unhandled exceptions will abort the crawl as opposed,
 * for example, to having the process hang indefinitely.
 */
@Slf4j
@Timeout(60)
@WithLogLevel(value = "OFF", classes = CrawlerTestHarness.class) // tests expect exceptions
class UnhandledExceptionJvmCrashTest {

    @Test
    void testUnhandledExceptionJvmCrash(@TempDir Path tempDir)
            throws Exception {
        var instrument = new CrawlerTestInstrument()
                .setDriverSupplierClass(WebCrawlerDriverFactory.class)
                .setRecordEvents(true)
                .setWorkDir(tempDir)
                .setNewJvm(false)
                .setClustered(false)
                .setConfigModifier(cfg -> {
                    cfg.setId("test-exception-crash");
                    cfg.addEventListener(new ThrowingEventListener());
                });

        try (var harness = new CrawlerTestHarness(instrument)) {
            RuntimeException thrown = null;
            try {
                harness.launchSync("node1");
            } catch (RuntimeException e) {
                thrown = e;
            }

            // The crawler must terminate quickly (not hang) — verified by @Timeout.
            // ThrowingEventListener throws on the first event, before any imports.
            var output = harness.getNodeOutput("node1");
            assertThat(output).isPresent();
            assertThat(output.get().getEventNameBag()
                    .getCount(CrawlerEvent.DOCUMENT_IMPORTED)).isZero();

            // If an exception was propagated, its cause should reference the
            // simulated error thrown by ThrowingEventListener.
            if (thrown != null) {
                assertThat(thrown.getCause())
                        .hasMessageContaining(
                                "Simulating unrecoverable exception");
            }
        }
    }
}
