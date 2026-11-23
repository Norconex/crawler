/* Copyright 2017-2026 Norconex Inc.
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
package com.norconex.importer.handler.transformer.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.Sleeper;
import com.norconex.commons.lang.map.Properties;
import com.norconex.importer.TestUtil;
import com.norconex.importer.handler.AbstractExternalTest;
import com.norconex.importer.handler.parser.ParseState;
import com.norconex.importer.util.ExternalApp;

@Timeout(30)
class ExternalTransformerTest extends AbstractExternalTest {

    public ExternalTransformerTest() {
        super(ExternalTransformer::new);
    }

    @Test
    void testCommandIsStoppedWhenWaitingForItIsInterrupted()
            throws InterruptedException {
        var before = ProcessHandle.current().children().count();
        var transformer = new ExternalTransformer();
        transformer.getConfiguration()
                .setCommand(ExternalApp.newCommandLine(""));
        var failure = new AtomicReference<Throwable>();

        // Interrupted before it starts: waiting for the command is cut short
        // right away, as when the crawler gives up on a document.
        var thread = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                transformer.handle(TestUtil.newHandlerContext("doc.txt",
                        new ByteArrayInputStream(
                                "content".getBytes(StandardCharsets.UTF_8)),
                        new Properties(), ParseState.PRE));
            } catch (Exception e) {
                failure.set(e);
            }
        });
        thread.start();
        thread.join(Duration.ofSeconds(20).toMillis());

        assertThat(failure.get()).isNotNull();
        var deadline = System.currentTimeMillis() + 10_000;
        while (ProcessHandle.current().children().count() > before
                && System.currentTimeMillis() < deadline) {
            Sleeper.sleepMillis(100);
        }
        assertThat(ProcessHandle.current().children().count())
                .isLessThanOrEqualTo(before);
    }
}
