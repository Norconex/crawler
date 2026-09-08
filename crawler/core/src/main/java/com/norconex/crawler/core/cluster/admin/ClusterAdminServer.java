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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
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
        var clusterConfig = config.getClusterConfig();
        var basePort = clusterConfig.getAdminPort();
        var bindAddress = resolveBindAddress(
                clusterConfig.getAdminBindAddress());

        // The administrative endpoints can stop a crawl and are guarded only
        // by a crawler-id header, which is an identifier and not a secret.
        // Binding beyond loopback is legitimate for clustered mode, but it is
        // worth saying out loud so it is never a surprise in an audit.
        if (bindAddress == null) {
            LOG.warn("Cluster admin server will bind to ALL network "
                    + "interfaces (adminBindAddress={}). Its endpoints, "
                    + "which include stopping the crawl, will be reachable "
                    + "from other hosts and are not protected by a secret. "
                    + "Set adminBindAddress to \"{}\" to restrict it to "
                    + "this host.",
                    ClusterConfig.ADMIN_BIND_ANY,
                    ClusterConfig.ADMIN_BIND_LOOPBACK);
        } else if (!bindAddress.isLoopbackAddress()) {
            LOG.warn("Cluster admin server will bind to {}, making its "
                    + "endpoints reachable from other hosts.", bindAddress);
        }

        if (basePort == 0) {
            try {
                return startHttpServer(0, bindAddress);
            } catch (IOException e) {
                throw new CrawlerException(
                        "Failed to start cluster admin HTTP server", e);
            }
        }
        var maxAttempts = 100;
        var port = basePort;
        for (var attempt = 0; attempt < maxAttempts; attempt++) {
            port = findAvailablePort(port, bindAddress);
            try {
                return startHttpServer(port, bindAddress);
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

    /**
     * Resolves the configured bind address.
     * @param value configured value; blank is treated as the default
     * @return the address to bind to, or <code>null</code> to bind every
     *     interface
     */
    static InetAddress resolveBindAddress(String value) {
        var address = StringUtils.trimToNull(value);
        if (address == null
                || ClusterConfig.ADMIN_BIND_LOOPBACK
                        .equalsIgnoreCase(address)) {
            return InetAddress.getLoopbackAddress();
        }
        if (ClusterConfig.ADMIN_BIND_ANY.equalsIgnoreCase(address)) {
            // A null address means the wildcard, i.e. every interface.
            return null;
        }
        try {
            return InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            // Failing closed here would silently disable administration;
            // failing loudly is right, since the operator asked for a
            // specific interface and did not get it.
            throw new CrawlerException(
                    "Cannot resolve cluster admin bind address \"" + address
                            + "\". Use \"" + ClusterConfig.ADMIN_BIND_LOOPBACK
                            + "\", \"" + ClusterConfig.ADMIN_BIND_ANY
                            + "\", or a resolvable host name or IP address.",
                    e);
        }
    }

    private int startHttpServer(int port, InetAddress bindAddress)
            throws IOException {
        var socketAddress = bindAddress == null
                ? new InetSocketAddress(port)
                : new InetSocketAddress(bindAddress, port);
        httpServer = HttpServer.create(socketAddress, 0);
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
     * The socket address the server actually bound to, or <code>null</code>
     * if it is not running. Useful for asserting the interface exposure is
     * what the configuration asked for.
     * @return bound address, or <code>null</code>
     */
    InetSocketAddress getBoundAddress() {
        return httpServer == null ? null : httpServer.getAddress();
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
     * <p>
     * The probe binds the same address the server will use. Probing the
     * wildcard address instead would give the wrong answer both ways: a port
     * held by another process on one interface would look taken when it is
     * free on ours, and vice versa.
     * </p>
     * @param startPort the port to start checking from
     * @param bindAddress address to test, or <code>null</code> for every
     *     interface
     * @return the available port
     */
    private int findAvailablePort(int startPort, InetAddress bindAddress) {
        for (var port = startPort; port < startPort + 100; port++) {
            try (var socket = new ServerSocket(port, 0, bindAddress)) {
                return port;
            } catch (IOException e) {
                // Port taken, try next
            }
        }
        throw new CrawlerException(
                "No available port found starting from " + startPort);
    }
}
