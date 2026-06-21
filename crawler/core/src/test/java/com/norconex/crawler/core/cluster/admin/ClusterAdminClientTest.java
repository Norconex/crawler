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
package com.norconex.crawler.core.cluster.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

@Isolated
@Timeout(30)
class ClusterAdminClientTest {

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        servers.forEach(server -> server.stop(0));
        servers.clear();
    }

    @Test
    void clusterStop_returnsTrueWhenNodeAcceptsRequest() throws Exception {
        var server = startServer(exchange -> respond(exchange, 200, "ok"));

        var stopped = ClusterAdminClient.builder()
                .crawlerId("crawler-1")
                .nodeUrl(baseUrl(server))
                .build()
                .clusterStop();

        assertThat(stopped).isTrue();
    }

    @Test
    void clusterStop_retriesUntilNodeReturnsSuccess() throws Exception {
        var failingServer =
                startServer(exchange -> respond(exchange, 500, "fail"));
        var successServer =
                startServer(exchange -> respond(exchange, 200, "ok"));

        var stopped = ClusterAdminClient.builder()
                .crawlerId("crawler-1")
                .nodeUrl(baseUrl(failingServer))
                .nodeUrl(baseUrl(successServer))
                .build()
                .clusterStop();

        assertThat(stopped).isTrue();
    }

    @Test
    void clusterStop_returnsFalseWhenNoNodeReturnsSuccess() throws Exception {
        var failingServer =
                startServer(exchange -> respond(exchange, 500, "denied"));

        var stopped = ClusterAdminClient.builder()
                .crawlerId("crawler-1")
                .nodeUrl(baseUrl(failingServer))
                .build()
                .clusterStop();

        assertThat(stopped).isFalse();
    }

    @Test
    void clusterSize_returnsParsedBody() throws Exception {
        var server = startServer(exchange -> respond(exchange, 200, "7"));

        var size = ClusterAdminClient.builder()
                .crawlerId("crawler-1")
                .nodeUrl(baseUrl(server))
                .build()
                .clusterSize();

        assertThat(size).isEqualTo(7);
    }

    @Test
    void clusterSize_usesNextNodeWhenFirstConnectionFails() throws Exception {
        var unavailableUrl = "http://localhost:" + freePort();
        var successServer =
                startServer(exchange -> respond(exchange, 200, "4"));

        var size = ClusterAdminClient.builder()
                .crawlerId("crawler-1")
                .nodeUrl(unavailableUrl)
                .nodeUrl(baseUrl(successServer))
                .build()
                .clusterSize();

        assertThat(size).isEqualTo(4);
    }

    @Test
    void clusterStop_throwsHelpfulExceptionWhenAllNodesAreUnavailable()
            throws Exception {
        var unavailableUrl = "http://localhost:" + freePort();

        assertThatThrownBy(() -> ClusterAdminClient.builder()
                .crawlerId("crawler-1")
                .nodeUrl(unavailableUrl)
                .build()
                .clusterStop())
                        .isInstanceOf(ClusterAdminException.class)
                        .hasMessageContaining(
                                "Could not connect to crawler endpoint")
                        .hasMessageContaining(
                                unavailableUrl + Endpoint.CLUSTER_STOP);
    }

    private HttpServer startServer(ExchangeHandler handler) throws IOException {
        var server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(Endpoint.CLUSTER_STOP.getPath(), exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.createContext(Endpoint.CLUSTER_SIZE.getPath(), exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        servers.add(server);
        return server;
    }

    private void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        exchange.sendResponseHeaders(status, body.getBytes().length);
        exchange.getResponseBody().write(body.getBytes());
    }

    private String baseUrl(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private int freePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
