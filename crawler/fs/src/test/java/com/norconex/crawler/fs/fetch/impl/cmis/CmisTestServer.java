/* Copyright 2014-2026 Norconex Inc.
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
package com.norconex.crawler.fs.fetch.impl.cmis;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@SuppressWarnings("null")
public class CmisTestServer {

    public static final String WS_1_0 = "/services";
    public static final String WS_1_1 = "/services11";
    public static final String ATOM_1_0 = "/atom";
    public static final String ATOM_1_1 = "/atom11";
    public static final String BROWSER = "/browser";

    private static final String REPOSITORY_ID = "test-repo";
    private static final String REPOSITORY_NAME = "Norconex CMIS Test Repo";
    private static final String LAST_MODIFIED =
            OffsetDateTime.parse("2024-01-01T00:00:00Z").toString();

    private static final List<String> ROOT_DOCUMENT_PATHS =
            IntStream.rangeClosed(1, 21)
                    .mapToObj(i -> "/doc" + i + ".txt")
                    .toList();

    private HttpServer server;

    private int localPort;
    private boolean initialized;

    private final List<ReceivedRequest> receivedRequests =
            Collections.synchronizedList(new ArrayList<>());
    private final Map<String, byte[]> documentContent = createContent();

    private synchronized void initServer() throws IOException {
        if (initialized) {
            return;
        }

        server = HttpServer.create(
                new InetSocketAddress("localhost", 0), 0);
        var handler = new CmisHttpHandler();
        server.createContext(ATOM_1_0, handler);
        server.createContext(ATOM_1_1, handler);

        initialized = true;
    }

    public void start() throws Exception {
        initServer();
        server.start();
        localPort = server.getAddress().getPort();
        System.out.println(
                "Test CMIS server has successfully started on port "
                        + localPort);
    }

    public void stop() throws Exception {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * @return the localPort
     */
    public int getLocalPort() {
        return localPort;
    }

    public List<ReceivedRequest> getReceivedRequests() {
        synchronized (receivedRequests) {
            return List.copyOf(receivedRequests);
        }
    }

    public static void main(String[] args) throws Exception {
        final var server = new CmisTestServer();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                try {
                    server.stop();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        new Thread() {
            @Override
            public void run() {
                try {
                    server.start();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }.start();
    }

    private class CmisHttpHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            var method = exchange.getRequestMethod();
            var requestPath = exchange.getRequestURI().getPath();
            var queryString =
                    exchange.getRequestURI().getRawQuery();
            var query = parseQueryString(queryString);

            receivedRequests
                    .add(new ReceivedRequest(method,
                            requestPath, query));

            if (!"GET".equalsIgnoreCase(method)) {
                sendText(exchange, 405, "Method not allowed",
                        "text/plain");
                return;
            }

            var bindingRoot =
                    requestPath.startsWith(ATOM_1_1)
                            ? ATOM_1_1
                            : ATOM_1_0;
            var relativePath = requestPath
                    .substring(bindingRoot.length());
            if (relativePath.isEmpty()
                    || "/".equals(relativePath)) {
                sendText(
                        exchange,
                        200,
                        serviceDocument(bindingRoot),
                        "application/atom+xml;type=feed");
                return;
            }

            if ("/object".equals(relativePath)) {
                var objectPath = queryParam(query, "path", "/");
                sendText(
                        exchange,
                        200,
                        objectEntry(bindingRoot,
                                objectPath),
                        "application/atom+xml;type=entry");
                return;
            }

            if ("/children".equals(relativePath)) {
                var objectPath = queryParam(query, "path", "/");
                sendText(
                        exchange,
                        200,
                        childrenFeed(objectPath),
                        "application/atom+xml;type=feed");
                return;
            }

            if ("/content".equals(relativePath)) {
                var objectPath = queryParam(query, "path", "");
                var content = documentContent.get(objectPath);
                if (content == null) {
                    sendText(exchange, 404, "Not found",
                            "text/plain");
                    return;
                }
                sendBytes(exchange, 200, content, "text/plain");
                return;
            }

            sendText(exchange, 404, "Not found", "text/plain");
        }
    }

    private String serviceDocument(String bindingRoot) {
        var rootUrl = "http://localhost:" + localPort + bindingRoot;
        return """
                <service>
                  <workspace>
                    <repositoryInfo>
                      <repositoryId>%s</repositoryId>
                      <repositoryName>%s</repositoryName>
                    </repositoryInfo>
                    <uritemplate>
                      <type>objectbypath</type>
                      <template>%s/object?path={path}</template>
                    </uritemplate>
                    <uritemplate>
                      <type>query</type>
                      <template>%s/query?q={q}</template>
                    </uritemplate>
                  </workspace>
                </service>
                """.formatted(REPOSITORY_ID, REPOSITORY_NAME, rootUrl, rootUrl);
    }

    private String objectEntry(String bindingRoot, String objectPath) {
        var normalized = normalizeObjectPath(objectPath);
        if ("/".equals(normalized)) {
            var downUrl = "http://localhost:" + localPort
                    + bindingRoot
                    + "/children?path=" + encode("/");
            return """
                    <entry>
                      <object>
                        <properties>
                          <propertyString propertyDefinitionId="cmis:objectTypeId"><value>cmis:folder</value></propertyString>
                          <propertyString propertyDefinitionId="cmis:baseTypeId"><value>cmis:folder</value></propertyString>
                          <propertyDateTime propertyDefinitionId="cmis:lastModificationDate"><value>%s</value></propertyDateTime>
                          <propertyInteger propertyDefinitionId="cmis:contentStreamLength"><value>0</value></propertyInteger>
                        </properties>
                      </object>
                      <link rel="down" type="application/atom+xml;type=feed" href="%s"/>
                    </entry>
                    """.formatted(LAST_MODIFIED, downUrl);
        }

        var content = documentContent.get(normalized);
        if (content == null) {
            return """
                    <entry>
                      <object>
                        <properties>
                          <propertyString propertyDefinitionId="cmis:objectTypeId"><value>cmis:folder</value></propertyString>
                          <propertyString propertyDefinitionId="cmis:baseTypeId"><value>cmis:folder</value></propertyString>
                          <propertyDateTime propertyDefinitionId="cmis:lastModificationDate"><value>%s</value></propertyDateTime>
                          <propertyInteger propertyDefinitionId="cmis:contentStreamLength"><value>0</value></propertyInteger>
                        </properties>
                      </object>
                    </entry>
                    """.formatted(LAST_MODIFIED);
        }

        var contentUrl = "http://localhost:" + localPort + bindingRoot
                + "/content?path=" + encode(normalized);
        return """
                <entry>
                  <object>
                    <properties>
                      <propertyString propertyDefinitionId="cmis:objectTypeId"><value>cmis:document</value></propertyString>
                      <propertyString propertyDefinitionId="cmis:baseTypeId"><value>cmis:document</value></propertyString>
                      <propertyDateTime propertyDefinitionId="cmis:lastModificationDate"><value>%s</value></propertyDateTime>
                      <propertyInteger propertyDefinitionId="cmis:contentStreamLength"><value>%d</value></propertyInteger>
                    </properties>
                  </object>
                  <content src="%s"/>
                </entry>
                """.formatted(LAST_MODIFIED, content.length, contentUrl);
    }

    private String childrenFeed(String objectPath) {
        var normalized = normalizeObjectPath(objectPath);
        if (!"/".equals(normalized)) {
            return """
                    <feed>
                      <numItems>0</numItems>
                    </feed>
                    """;
        }
        var entries = ROOT_DOCUMENT_PATHS.stream()
                .map(path -> "<entry><pathSegment>"
                        + path.substring(1)
                        + "</pathSegment></entry>")
                .reduce("", String::concat);

        return """
                <feed>
                  <numItems>%d</numItems>
                  %s
                </feed>
                """.formatted(ROOT_DOCUMENT_PATHS.size(), entries);
    }

    private static String normalizeObjectPath(String objectPath) {
        if (objectPath == null || objectPath.isBlank()) {
            return "/";
        }
        if (!objectPath.startsWith("/")) {
            return "/" + objectPath;
        }
        return objectPath;
    }

    private static String queryParam(
            Map<String, List<String>> query, String key,
            String defaultValue) {
        var values = query.get(key);
        if (values == null || values.isEmpty()) {
            return defaultValue;
        }
        return values.get(0);
    }

    private static Map<String, List<String>>
            parseQueryString(String query) {
        var result = new LinkedHashMap<String, List<String>>();
        if (query == null || query.isBlank()) {
            return result;
        }
        for (var pair : query.split("&")) {
            var idx = pair.indexOf('=');
            var rawKey = idx >= 0 ? pair.substring(0, idx) : pair;
            var rawVal = idx >= 0 ? pair.substring(idx + 1) : "";
            var key = urlDecode(rawKey);
            var value = urlDecode(rawVal);
            result.computeIfAbsent(key, unused -> new ArrayList<>())
                    .add(value);
        }
        return result;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, UTF_8);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, UTF_8);
    }

    private Map<String, byte[]> createContent() {
        var result = new LinkedHashMap<String, byte[]>();
        ROOT_DOCUMENT_PATHS.forEach(path -> result.put(
                path,
                ("content of " + path).getBytes(UTF_8)));
        return result;
    }

    private static void sendText(
            HttpExchange exchange,
            int status,
            String response,
            String contentType) throws IOException {
        sendBytes(exchange, status, response.getBytes(UTF_8),
                contentType);
    }

    private static void sendBytes(
            HttpExchange exchange,
            int status,
            byte[] response,
            String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, response.length);
        try (var body = exchange.getResponseBody()) {
            body.write(response);
        }
    }

    public static class ReceivedRequest {
        private final String method;
        private final String path;
        private final Map<String, List<String>> queryStringParameters;

        public ReceivedRequest(
                String method,
                String path,
                Map<String, List<
                        String>> queryStringParameters) {
            this.method = method;
            this.path = path;
            this.queryStringParameters =
                    Map.copyOf(queryStringParameters);
        }

        public String getMethod() {
            return method;
        }

        public String getPath() {
            return path;
        }

        public Map<String, List<String>> getQueryStringParameters() {
            return queryStringParameters;
        }
    }
}
