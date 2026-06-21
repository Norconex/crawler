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
package com.norconex.crawler.core.cluster.admin;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;

import org.apache.commons.lang3.StringUtils;

import com.norconex.crawler.core.CrawlerException;
import com.norconex.crawler.core.cluster.Cluster;
import com.norconex.crawler.core.cluster.ClusterConfig;
import com.norconex.crawler.core.session.CrawlerSession;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Provides an HTTP admin interface for cluster management.
 * Offers endpoints for querying cluster status and controlling the cluster.
 */
@Slf4j
public class ClusterAdminServer {

    private static final String GET = "GET";
    private static final String POST = "POST";

    private static final String TEXT_PLAIN = "text/plain";

    private final Cluster cluster;
    private final CrawlerSession session;
    private HttpServer httpServer;
    @Getter
    private int port;

    public ClusterAdminServer(CrawlerSession session) {
        this.session = session;
        cluster = session.getCluster();
    }

    /**
     * File name written to the crawler work directory containing the actual
     * admin server port. Used by the stop command to locate the server.
     */
    public static final String ADMIN_PORT_FILE = "admin.port";

    /**
     * Starts the HTTP server on an available port starting from
     * {@value ClusterConfig#DEFAULT_ADMIN_PORT}.
     * @return the server port
     */
    public int start() {
        port = doStart();
        writePortFile(port);
        return port;
    }

    private void writePortFile(int port) {
        var workDir = session.getCrawlContext().getWorkDir();
        if (workDir != null) {
            try {
                Files.createDirectories(workDir);
                Files.writeString(
                        workDir.resolve(ADMIN_PORT_FILE),
                        String.valueOf(port));
            } catch (IOException e) {
                LOG.warn("Could not write admin port file.", e);
            }
        }
    }

    public int doStart() {
        var config = session.getCrawlContext().getCrawlConfig();
        var basePort = config.getClusterConfig().getAdminPort();
        if (basePort == 0) {
            try {
                return startHttpServer(0);
            } catch (IOException e) {
                throw new CrawlerException(
                        "Failed to start cluster admin HTTP server", e);
            }
        }
        var maxAttempts = 100;
        var port = basePort;
        for (var attempt = 0; attempt < maxAttempts; attempt++) {
            port = findAvailablePort(port);
            try {
                return startHttpServer(port);
            } catch (IOException e) {
                if (e instanceof BindException) {
                    LOG.warn("Port {} is taken after findAvailablePort, "
                            + "trying next...", port);
                    port++;
                    continue;
                }
                throw new CrawlerException(
                        "Failed to start cluster admin HTTP server", e);
            }
        }
        throw new CrawlerException(
                "No available port found starting from " + basePort);
    }

    private int startHttpServer(int port) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        var actualPort = httpServer.getAddress().getPort();
        endpoint(GET, Endpoint.CLUSTER_SIZE, TEXT_PLAIN, exchange -> {
            sendResponse(exchange, 200,
                    String.valueOf(cluster.getNodeCount()));
        });
        endpoint(GET, Endpoint.CLUSTER_NODES, TEXT_PLAIN, exchange -> {
            sendResponse(exchange, 200,
                    String.join(",", cluster.getNodeNames()));
        });
        endpoint(POST, Endpoint.CLUSTER_STOP, TEXT_PLAIN, exchange -> {
            cluster.stop();
            sendResponse(exchange, 200, "Stopping cluster");
        });
        httpServer.setExecutor(null); // Use default executor
        httpServer.start();
        LOG.info("Cluster admin HTTP server started on port {}", actualPort);
        return actualPort;
    }

    /**
     * Stops the HTTP server.
     */
    public void close() {
        if (httpServer != null) {
            httpServer.stop(0);
            LOG.info("Cluster admin HTTP server stopped.");
        }
        var workDir = session.getCrawlContext().getWorkDir();
        if (workDir != null) {
            try {
                Files.deleteIfExists(workDir.resolve(ADMIN_PORT_FILE));
            } catch (IOException e) {
                LOG.warn("Could not delete admin port file.", e);
            }
        }
    }

    private void endpoint(
            String requestMethod,
            Endpoint endpoint,
            String responseContentType,
            HttpHandler handler) {
        httpServer.createContext(endpoint.getPath(), exchange -> {
            try {
                // Validate request
                if (!requestMethod.equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1); // Method Not Allowed
                    return;
                }
                if (!session.getCrawlerId().equals(
                        exchange.getRequestHeaders().getFirst("crawler-id"))) {
                    exchange.sendResponseHeaders(412, -1); // Precondition Failed
                    return;
                }

                // Handle response
                exchange.getResponseHeaders().set(
                        "Content-Type", responseContentType);
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
    }

    private void sendResponse(
            HttpExchange exchange, int respCode, String respBody)
            throws IOException {
        var msg = StringUtils.trimToEmpty(respBody);
        exchange.sendResponseHeaders(respCode, msg.length());
        try (var os = exchange.getResponseBody()) {
            os.write(msg.getBytes());
        }
    }

    /**
     * Finds the next available port starting from the given port.
     * @param startPort the port to start checking from
     * @return the available port
     */
    private int findAvailablePort(int startPort) {
        for (var port = startPort; port < startPort + 100; port++) {
            try (var socket = new ServerSocket(port)) {
                return port;
            } catch (IOException e) {
                // Port taken, try next
            }
        }
        throw new CrawlerException(
                "No available port found starting from " + startPort);
    }
}
