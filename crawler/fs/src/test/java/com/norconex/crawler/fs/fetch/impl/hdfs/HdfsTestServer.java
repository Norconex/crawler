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
package com.norconex.crawler.fs.fetch.impl.hdfs;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A minimal fake WebHDFS server (GETFILESTATUS/LISTSTATUS/OPEN) backed by
 * a real local directory, letting {@link HdfsFetcherIT} reuse the same
 * shared test fileset (and {@code AbstractFileFetcherTest} assertions) as
 * every other file-system fetcher's integration test.
 */
class HdfsTestServer {

    private final Path rootDir;
    private HttpServer server;
    private int localPort;

    HdfsTestServer(Path rootDir) {
        this.rootDir = rootDir;
    }

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/webhdfs/v1", this::handle);
        server.start();
        localPort = server.getAddress().getPort();
    }

    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    int getLocalPort() {
        return localPort;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            var op = queryParam(exchange, "op");
            var relPath = exchange.getRequestURI().getPath()
                    .substring("/webhdfs/v1".length());
            var target = resolve(relPath);
            switch (op == null ? "" : op.toUpperCase()) {
                case "GETFILESTATUS" -> handleGetFileStatus(exchange, target);
                case "LISTSTATUS" -> handleListStatus(exchange, target);
                case "OPEN" -> handleOpen(exchange, target);
                default -> sendJson(
                        exchange, 400,
                        "{\"RemoteException\":{\"message\":"
                                + "\"Unsupported op\"}}");
            }
        } finally {
            exchange.close();
        }
    }

    private Path resolve(String relPath) {
        var normalized = relPath.startsWith("/")
                ? relPath.substring(1)
                : relPath;
        return rootDir.resolve(normalized).normalize();
    }

    private void handleGetFileStatus(HttpExchange exchange, Path target)
            throws IOException {
        if (!Files.exists(target)) {
            sendJson(
                    exchange, 404,
                    "{\"RemoteException\":{\"message\":"
                            + "\"File not found\"}}");
            return;
        }
        sendJson(
                exchange, 200,
                "{\"FileStatus\":" + fileStatusJson(target) + "}");
    }

    private void handleListStatus(HttpExchange exchange, Path target)
            throws IOException {
        if (!Files.isDirectory(target)) {
            sendJson(
                    exchange, 404,
                    "{\"RemoteException\":{\"message\":"
                            + "\"Not a directory\"}}");
            return;
        }
        var children = new StringBuilder();
        try (var stream = Files.list(target)) {
            var first = true;
            for (var child : stream.sorted().toList()) {
                if (!first) {
                    children.append(",");
                }
                first = false;
                children.append(fileStatusJson(child));
            }
        }
        sendJson(
                exchange, 200,
                "{\"FileStatuses\":{\"FileStatus\":["
                        + children + "]}}");
    }

    private void handleOpen(HttpExchange exchange, Path target)
            throws IOException {
        if (!Files.isRegularFile(target)) {
            sendJson(
                    exchange, 404,
                    "{\"RemoteException\":{\"message\":"
                            + "\"File not found\"}}");
            return;
        }
        var content = Files.readAllBytes(target);
        exchange.getResponseHeaders().set(
                "Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(200, content.length);
        try (var body = exchange.getResponseBody()) {
            body.write(content);
        }
    }

    private static String fileStatusJson(Path p) {
        var dir = Files.isDirectory(p);
        long length = 0;
        long modTime = 0;
        try {
            length = dir ? 0 : Files.size(p);
            modTime = Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            // Leave defaults; this is test-server-only best-effort.
        }
        return """
                {"pathSuffix":"%s","type":"%s","length":%d,\
                "modificationTime":%d,"accessTime":%d}"""
                .formatted(
                        p.getFileName().toString(),
                        dir ? "DIRECTORY" : "FILE", length, modTime, modTime);
    }

    private static String queryParam(HttpExchange exchange, String name) {
        var query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (var pair : query.split("&")) {
            var idx = pair.indexOf('=');
            var key = idx >= 0 ? pair.substring(0, idx) : pair;
            if (key.equals(name)) {
                return idx >= 0 ? pair.substring(idx + 1) : "";
            }
        }
        return null;
    }

    private static void sendJson(
            HttpExchange exchange, int status, String json)
            throws IOException {
        var bytes = json.getBytes(UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var body = exchange.getResponseBody()) {
            body.write(bytes);
        }
    }
}
