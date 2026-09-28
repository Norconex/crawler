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
package com.norconex.importer.handler.transformer.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.event.EventManager;
import com.norconex.importer.TestUtil;
import com.norconex.importer.handler.DocHandlerCache;
import com.norconex.importer.handler.DocHandlerContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

@Isolated
@Timeout(30)
class TextEmbeddingTransformerTest {

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        servers.forEach(server -> server.stop(0));
        servers.clear();
    }

    @Test
    void testEmbedsTextAndStoresVector() throws IOException {
        var server = startServer(200,
                "{\"data\":[{\"embedding\":[0.1,0.2,0.3],\"index\":0}]}");

        var t = new TextEmbeddingTransformer();
        t.getConfiguration()
                .setApiUrl(baseUrl(server) + "/embeddings")
                .setModel("test-model")
                .setTargetField("embedding");

        var docCtx = TestUtil.newHandlerContext("some text to embed");
        t.handle(docCtx);

        assertThat(docCtx.metadata().getStrings("embedding"))
                .containsExactly("0.1", "0.2", "0.3");
    }

    @Test
    void testBlankTargetFieldFallsBackToDefault() throws IOException {
        var server = startServer(200,
                "{\"data\":[{\"embedding\":[0.5],\"index\":0}]}");

        var t = new TextEmbeddingTransformer();
        t.getConfiguration()
                .setApiUrl(baseUrl(server) + "/embeddings")
                .setModel("test-model")
                .setTargetField("");

        var docCtx = TestUtil.newHandlerContext("some text");
        t.handle(docCtx);

        assertThat(docCtx.metadata().getStrings("embedding"))
                .containsExactly("0.5");
    }

    @Test
    void testNonPositiveTimeoutFallsBackToDefault() throws IOException {
        var server = startServer(200,
                "{\"data\":[{\"embedding\":[0.5],\"index\":0}]}");

        var t = new TextEmbeddingTransformer();
        t.getConfiguration()
                .setApiUrl(baseUrl(server) + "/embeddings")
                .setModel("test-model")
                .setTimeoutSeconds(0);

        var docCtx = TestUtil.newHandlerContext("some text");
        assertThatNoException().isThrownBy(() -> t.handle(docCtx));
        assertThat(docCtx.metadata().getStrings("embedding"))
                .containsExactly("0.5");
    }

    @Test
    void testBlankTextIsSkipped() throws IOException {
        var t = new TextEmbeddingTransformer();
        t.getConfiguration()
                .setApiUrl("http://localhost:1/embeddings")
                .setModel("test-model");

        var docCtx = TestUtil.newHandlerContext("   ");
        var keepGoing = t.handle(docCtx);

        assertThat(keepGoing).isTrue();
        assertThat(docCtx.metadata().getStrings("embedding")).isEmpty();
    }

    @Test
    void testMissingApiUrlThrows() {
        var t = new TextEmbeddingTransformer();
        t.getConfiguration().setModel("test-model");

        var docCtx = TestUtil.newHandlerContext("some text");
        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> t.handle(docCtx))
                .withMessageContaining("apiUrl");
    }

    @Test
    void testErrorResponseThrows() throws IOException {
        var server = startServer(500, "server exploded");

        var t = new TextEmbeddingTransformer();
        t.getConfiguration()
                .setApiUrl(baseUrl(server) + "/embeddings")
                .setModel("test-model");

        var docCtx = TestUtil.newHandlerContext("some text");
        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> t.handle(docCtx))
                .withMessageContaining("500");
    }

    @Test
    void testRepeatedTextReusesCachedVectorInsteadOfCallingApiAgain()
            throws IOException {
        var calls = new AtomicInteger();
        var server = startServer(calls, 200,
                "{\"data\":[{\"embedding\":[0.1,0.2],\"index\":0}]}");

        var t = new TextEmbeddingTransformer();
        t.getConfiguration()
                .setApiUrl(baseUrl(server) + "/embeddings")
                .setModel("test-model");

        var cache = mapCache(new HashMap<>());
        var first = contextWithCache("same text", cache);
        var second = contextWithCache("same text", cache);
        t.handle(first);
        t.handle(second);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(first.metadata().getStrings("embedding"))
                .isEqualTo(second.metadata().getStrings("embedding"))
                .containsExactly("0.1", "0.2");
    }

    @Test
    void testDifferentModelsDoNotShareACacheEntry() throws IOException {
        var calls = new AtomicInteger();
        var server = startServer(calls, 200,
                "{\"data\":[{\"embedding\":[0.1,0.2],\"index\":0}]}");

        var cache = mapCache(new HashMap<>());

        var t1 = new TextEmbeddingTransformer();
        t1.getConfiguration()
                .setApiUrl(baseUrl(server) + "/embeddings")
                .setModel("model-one");
        t1.handle(contextWithCache("same text", cache));

        var t2 = new TextEmbeddingTransformer();
        t2.getConfiguration()
                .setApiUrl(baseUrl(server) + "/embeddings")
                .setModel("model-two");
        t2.handle(contextWithCache("same text", cache));

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void testWithoutARealCacheEveryCallReachesTheApi() throws IOException {
        var calls = new AtomicInteger();
        var server = startServer(calls, 200,
                "{\"data\":[{\"embedding\":[0.1,0.2],\"index\":0}]}");

        var t = new TextEmbeddingTransformer();
        t.getConfiguration()
                .setApiUrl(baseUrl(server) + "/embeddings")
                .setModel("test-model");

        t.handle(TestUtil.newHandlerContext("same text"));
        t.handle(TestUtil.newHandlerContext("same text"));

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void testWriteRead() {
        var t = new TextEmbeddingTransformer();
        t.getConfiguration()
                .setApiUrl("https://api.openai.com/v1/embeddings")
                .setApiKey("secret")
                .setModel("text-embedding-3-small")
                .setTargetField("vector")
                .setTimeoutSeconds(30);
        assertThatNoException()
                .isThrownBy(() -> BeanMapper.DEFAULT.assertWriteRead(t));
    }

    private HttpServer startServer(int status, String body)
            throws IOException {
        return startServer(null, status, body);
    }

    private HttpServer startServer(
            AtomicInteger callCount, int status, String body)
            throws IOException {
        var server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/embeddings", exchange -> {
            try {
                if (callCount != null) {
                    callCount.incrementAndGet();
                }
                respond(exchange, status, body);
            } finally {
                exchange.close();
            }
        });
        server.start();
        servers.add(server);
        return server;
    }

    // A trivial, non-clustered stand-in for the cache a crawler would
    // supply, sufficient to prove the handler consults and populates it.
    private DocHandlerCache mapCache(Map<String, String> store) {
        return (key, loader) -> {
            if (store.containsKey(key)) {
                return store.get(key);
            }
            var value = loader.load(key);
            store.put(key, value);
            return value;
        };
    }

    private DocHandlerContext contextWithCache(String body,
            DocHandlerCache cache) {
        return DocHandlerContext.builder()
                .doc(TestUtil.newDoc(
                        "dummy-ref", new ByteArrayInputStream(body.getBytes())))
                .eventManager(new EventManager())
                .cache(cache)
                .build();
    }

    private void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        var bytes = body.getBytes();
        exchange.sendResponseHeaders(status, bytes.length);
        IOUtils.write(bytes, exchange.getResponseBody());
    }

    private String baseUrl(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }
}
