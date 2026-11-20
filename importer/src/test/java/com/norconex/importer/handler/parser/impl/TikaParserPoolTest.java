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
package com.norconex.importer.handler.parser.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AbstractParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

@Timeout(20)
class TikaParserPoolTest {
    @Test
    void testLegacyAndInvalidSize() throws Exception {
        var parser = new StubParser((m, c) -> {});
        assertThat(TikaParserPool.create(0, () -> parser)).isSameAs(parser);
        assertThatThrownBy(() -> TikaParserPool.create(-1, () -> parser))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefaultParserConfig().setPoolSize(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TikaParserConfig().setPoolSize(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testIndependentParsersAndBoundedConcurrency() throws Exception {
        var created = new AtomicInteger();
        var active = new AtomicInteger();
        var maxActive = new AtomicInteger();
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var parser = TikaParserPool.create(2, () -> {
            created.incrementAndGet();
            var busy = new AtomicBoolean();
            return new StubParser((m, c) -> {
                assertThat(busy.compareAndSet(false, true)).isTrue();
                maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                entered.countDown();
                try {
                    await(release);
                } finally {
                    active.decrementAndGet();
                    busy.set(false);
                }
            });
        });
        try (var executor = Executors.newFixedThreadPool(6)) {
            var futures = new ArrayList<Future<?>>();
            try {
                for (int i = 0; i < 12; i++) {
                    futures.add(executor.submit(() -> {
                        parse(parser);
                        return null;
                    }));
                }
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(active).hasValue(2);
            } finally {
                release.countDown();
            }
            for (var future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        }
        assertThat(created).hasValue(2);
        assertThat(maxActive).hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(strings = { "io", "sax", "tika", "runtime" })
    void testReturnedAfterFailure(String failure) throws Exception {
        var calls = new AtomicInteger();
        var parser = TikaParserPool.create(1, () -> new StubParser((m, c) -> {
            if (calls.incrementAndGet() == 1) {
                switch (failure) {
                    case "io" -> throw new IOException("test");
                    case "sax" -> throw new SAXException("test");
                    case "tika" -> throw new TikaException("test");
                    default -> throw new IllegalStateException("test");
                }
            }
        }));
        assertThatThrownBy(() -> parse(parser)).hasMessage("test");
        parse(parser);
        assertThat(calls).hasValue(2);
    }

    @Test
    void testRecursiveParseReusesBorrowedParser() throws Exception {
        var calls = new AtomicInteger();
        var parser = TikaParserPool.create(1, () -> new StubParser((m, c) -> {
            if (calls.incrementAndGet() == 1) {
                c.get(Parser.class).parse(InputStream.nullInputStream(),
                        new DefaultHandler(), m, c);
            }
        }));
        var context = new ParseContext();
        context.set(Parser.class, parser);
        parser.parse(InputStream.nullInputStream(), new DefaultHandler(),
                new Metadata(), context);
        assertThat(calls).hasValue(2);
        assertThat(parser.getSupportedTypes(context))
                .contains(MediaType.TEXT_PLAIN);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void testWaitingCallerInterruptedOrClosed(boolean close) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var waiting = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        var parser = TikaParserPool.create(1, () -> new StubParser((m, c) -> {
            entered.countDown();
            await(release);
        }));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var owner = executor.submit(() -> {
                parse(parser);
                return null;
            });
            Thread waiter = null;
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                waiter = new Thread(() -> {
                    waiting.countDown();
                    try {
                        parse(parser);
                    } catch (Throwable e) {
                        failure.set(e);
                        interrupted.set(Thread.currentThread().isInterrupted());
                    }
                });
                waiter.start();
                assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
                // Wait for the actual pool wait, rather than relying on a sleep.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (waiter.getState() != Thread.State.WAITING
                        && System.nanoTime() < deadline) {
                    Thread.yield();
                }
                assertThat(waiter.getState()).isEqualTo(Thread.State.WAITING);
                if (close) {
                    ((TikaParserPool) parser).close();
                } else {
                    waiter.interrupt();
                }
                waiter.join(5000);
                assertThat(waiter.isAlive()).isFalse();
                if (close) {
                    assertThat(failure.get()).isInstanceOf(IOException.class)
                            .hasMessageContaining("closed");
                } else {
                    assertThat(failure.get())
                            .isInstanceOf(InterruptedIOException.class);
                    assertThat(interrupted).isTrue();
                }
            } finally {
                release.countDown();
                if (waiter != null) {
                    waiter.interrupt();
                    waiter.join(5000);
                }
            }
            owner.get(5, TimeUnit.SECONDS);
        }
        if (close) {
            assertThatThrownBy(() -> parse(parser))
                    .hasMessageContaining("closed");
        } else {
            parse(parser);
        }
    }

    @Test
    void testInitializationFailurePropagates() {
        assertThatThrownBy(() -> TikaParserPool.create(2, () -> {
            throw new IOException("invalid config");
        })).isInstanceOf(IOException.class).hasMessage("invalid config");
    }

    private static void parse(Parser parser) throws Exception {
        parser.parse(InputStream.nullInputStream(), new DefaultHandler(),
                new Metadata(), new ParseContext());
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IOException("Test latch timed out.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    @FunctionalInterface
    private interface ParseAction {
        void parse(Metadata metadata, ParseContext context)
                throws IOException, SAXException, TikaException;
    }

    private static class StubParser extends AbstractParser {
        private static final long serialVersionUID = 1L;
        private final ParseAction action;

        StubParser(ParseAction action) {
            this.action = action;
        }

        @Override
        public Set<MediaType> getSupportedTypes(ParseContext context) {
            return Set.of(MediaType.TEXT_PLAIN);
        }

        @Override
        public void parse(InputStream stream, ContentHandler handler,
                Metadata metadata, ParseContext context)
                throws IOException, SAXException, TikaException {
            action.parse(metadata, context);
        }
    }
}
