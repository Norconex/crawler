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
package com.norconex.committer.solr;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.committer.core.CommitterException;
import com.norconex.commons.lang.security.Credentials;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

@Timeout(30)
class SolrHttpClientTest {

    private static final String OK = "{\"responseHeader\":{\"status\":0}}";

    /** A fake Solr node recording what it receives. */
    private static final class Node {
        final HttpServer server;
        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<Map<String, String>> headers = new CopyOnWriteArrayList<>();
        final List<String> bodies = new CopyOnWriteArrayList<>();
        volatile Function<HttpExchange, Integer> status = x -> 200;
        volatile String response = OK;

        Node() throws IOException {
            server = HttpServer.create(new InetSocketAddress("localhost", 0),
                    0);
            server.createContext("/", x -> {
                requests.add(x.getRequestMethod() + " "
                        + x.getRequestURI());
                var h = new java.util.HashMap<String, String>();
                x.getRequestHeaders().forEach(
                        (k, v) -> h.put(k.toLowerCase(), v.get(0)));
                headers.add(h);
                bodies.add(
                        new String(x.getRequestBody().readAllBytes(), UTF_8));
                var code = status.apply(x);
                var bytes = response.getBytes(UTF_8);
                x.sendResponseHeaders(code, bytes.length);
                x.getResponseBody().write(bytes);
                x.close();
            });
            server.start();
        }

        String url(String path) {
            return "http://localhost:" + server.getAddress().getPort() + path;
        }

        void stop() {
            server.stop(0);
        }
    }

    private final List<Node> nodes = new ArrayList<>();

    private Node node() throws IOException {
        var n = new Node();
        nodes.add(n);
        return n;
    }

    @AfterEach
    void tearDown() {
        nodes.forEach(Node::stop);
    }

    private static SolrCommitterConfig config(
            SolrConnectionMode mode, String urls) {
        var cfg = new SolrCommitterConfig();
        cfg.setConnectionMode(mode);
        cfg.setNodes(Arrays.stream(urls.split(",")).map(String::trim)
                .toList());
        return cfg;
    }

    private static void send(SolrHttpClient c) throws CommitterException {
        c.update("{}".getBytes(UTF_8), Map.of("commit", "true"));
    }

    @Test
    void testSingleNode() throws Exception {
        var n = node();
        try (var c = new SolrHttpClient(
                config(SolrConnectionMode.SINGLE_NODE, n.url("/solr/test/")))) {
            var params = new LinkedHashMap<String, String>();
            params.put("commit", "true");
            params.put("a b", "c&d");
            c.update("{\"a\":1}".getBytes(UTF_8), params);
        }
        assertThat(n.requests).containsExactly(
                "POST /solr/test/update?wt=json&commit=true&a+b=c%26d");
        assertThat(n.bodies).containsExactly("{\"a\":1}");
        assertThat(n.headers.get(0))
                .containsEntry("content-type",
                        "application/json; charset=UTF-8")
                .doesNotContainKey("authorization");
    }

    @Test
    void testBasicAuth() throws Exception {
        var n = node();
        var cfg = config(SolrConnectionMode.SINGLE_NODE, n.url("/solr/test"));
        var creds = new Credentials();
        creds.setUsername("user");
        creds.setPassword("pass");
        cfg.setCredentials(creds);
        cfg.setBearerToken("ignored");
        try (var c = new SolrHttpClient(cfg)) {
            send(c);
        }
        // base64 of user:pass
        assertThat(n.headers.get(0))
                .containsEntry("authorization", "Basic dXNlcjpwYXNz");
    }

    @Test
    void testBearerTokenAndCustomHeaders() throws Exception {
        var n = node();
        var cfg = config(SolrConnectionMode.SINGLE_NODE, n.url("/solr/test"));
        cfg.setBearerToken("tok");
        cfg.setHeader("X-Api-Key", "k1");
        try (var c = new SolrHttpClient(cfg)) {
            send(c);
        }
        assertThat(n.headers.get(0))
                .containsEntry("authorization", "Bearer tok")
                .containsEntry("x-api-key", "k1");

        // custom headers can override the authorization one
        cfg.setHeader("Authorization", "ApiKey abc");
        try (var c = new SolrHttpClient(cfg)) {
            send(c);
        }
        assertThat(n.headers.get(1))
                .containsEntry("authorization", "ApiKey abc");
    }

    @Test
    void testSolrErrorIsNotRetried() throws Exception {
        var n1 = node();
        var n2 = node();
        for (var n : List.of(n1, n2)) {
            n.status = x -> 400;
            n.response =
                    "{\"error\":{\"msg\":\"unknown field 'zzz'\",\"code\":400}}";
        }
        try (var c = new SolrHttpClient(config(
                SolrConnectionMode.LOAD_BALANCED,
                n1.url("/solr/t") + "," + n2.url("/solr/t")))) {
            assertThatExceptionOfType(CommitterException.class)
                    .isThrownBy(() -> send(c))
                    .withMessageContaining("HTTP 400")
                    .withMessageContaining("unknown field 'zzz'");
        }
        assertThat(n1.requests.size() + n2.requests.size()).isEqualTo(1);
    }

    @Test
    void testBadSolrStatus() throws Exception {
        var n = node();
        n.response = "{\"responseHeader\":{\"status\":500}}";
        try (var c = new SolrHttpClient(
                config(SolrConnectionMode.SINGLE_NODE, n.url("/solr/t")))) {
            assertThatExceptionOfType(CommitterException.class)
                    .isThrownBy(() -> send(c))
                    .withMessageContaining("Invalid Solr response status: 500");
        }
        n.response = "<html>not solr</html>";
        try (var c = new SolrHttpClient(
                config(SolrConnectionMode.SINGLE_NODE, n.url("/solr/t")))) {
            assertThatExceptionOfType(CommitterException.class)
                    .isThrownBy(() -> send(c))
                    .withMessageContaining("Invalid response from Solr");
        }
    }

    @Test
    void testLoadBalancingAndFailover() throws Exception {
        var good = node();
        var bad = node();
        bad.status = x -> 503;
        var urls = bad.url("/solr/t") + ", " + good.url("/solr/t");
        try (var c = new SolrHttpClient(
                config(SolrConnectionMode.LOAD_BALANCED, urls))) {
            for (var i = 0; i < 6; i++) {
                send(c);
            }
        }
        // all succeeded on the good node; bad was tried once then set aside
        assertThat(good.requests).hasSize(6);
        assertThat(bad.requests.size()).isLessThanOrEqualTo(1);
    }

    @Test
    void testRoundRobin() throws Exception {
        var n1 = node();
        var n2 = node();
        try (var c = new SolrHttpClient(config(
                SolrConnectionMode.LOAD_BALANCED,
                n1.url("/solr/t") + "," + n2.url("/solr/t")))) {
            for (var i = 0; i < 6; i++) {
                send(c);
            }
        }
        assertThat(n1.requests).hasSize(3);
        assertThat(n2.requests).hasSize(3);
    }

    @Test
    void testDeadNodeFallsBackToIt() throws Exception {
        // an unreachable node (stopped server) and a good one
        var dead = node();
        var deadUrl = dead.url("/solr/t");
        dead.stop();
        var good = node();
        try (var c = new SolrHttpClient(config(
                SolrConnectionMode.LOAD_BALANCED,
                deadUrl + "," + good.url("/solr/t")))) {
            assertThatCode(() -> {
                send(c);
                send(c);
            }).doesNotThrowAnyException();
        }
        assertThat(good.requests).hasSize(2);
    }

    @Test
    void testAllNodesDown() throws Exception {
        var n = node();
        var url = n.url("/solr/t");
        n.stop();
        try (var c = new SolrHttpClient(
                config(SolrConnectionMode.SINGLE_NODE, url))) {
            assertThatExceptionOfType(CommitterException.class)
                    .isThrownBy(() -> send(c))
                    .withMessageContaining("Could not send documents to Solr");
        }
    }

    @Test
    void testRetryOnSingleNodeRecovers() throws Exception {
        var n = node();
        var calls = new int[1];
        n.status = x -> calls[0]++ == 0 ? 503 : 200;
        try (var c = new SolrHttpClient(
                config(SolrConnectionMode.SINGLE_NODE, n.url("/solr/t")))) {
            send(c);
        }
        assertThat(n.requests).hasSize(2);
    }

    @Test
    void testZookeeperConnectionStringRejected() {
        assertThatExceptionOfType(CommitterException.class)
                .isThrownBy(
                        () -> new SolrHttpClient(config(
                                SolrConnectionMode.CLOUD, "zk1:2181,zk2:2181")))
                .withMessageContaining("ZooKeeper");
        assertThatExceptionOfType(CommitterException.class)
                .isThrownBy(() -> new SolrHttpClient(config(
                        SolrConnectionMode.SINGLE_NODE, "")))
                .withMessageContaining("nodes");
    }

    //--- Cloud ----------------------------------------------------------

    @Test
    void testCloudDiscoversNodesHostingCollection() throws Exception {
        var seed = node();
        var other = node();
        // cluster state returned by the seed
        var otherBase = other.url("/solr");
        seed.response = """
            {"responseHeader":{"status":0},"cluster":{
              "collections":{"coll":{"shards":{
                "shard1":{"replicas":{
                  "r1":{"state":"active","node_name":"n2_solr",
                        "base_url":"%s"},
                  "r2":{"state":"down","node_name":"n3_solr",
                        "base_url":"http://down:8983/solr"}}}}}},
              "live_nodes":["n2_solr"]}}
            """.formatted(otherBase);
        // the seed only answers cluster status; updates go to "other"
        seed.status = x -> x.getRequestURI().getPath().endsWith("/update")
                ? 503
                : 200;
        try (var c = new SolrHttpClient(config(
                SolrConnectionMode.CLOUD, seed.url("/solr/coll")))) {
            send(c);
        }
        assertThat(seed.requests.get(0)).startsWith(
                "GET /solr/admin/collections?action=CLUSTERSTATUS");
        assertThat(seed.requests.get(0)).contains("collection=coll");
        assertThat(other.requests).hasSize(1);
        assertThat(other.requests.get(0)).startsWith("POST /solr/coll/update?");
        // the unreachable "down" replica is not used
        assertThat(seed.requests.stream().filter(r -> r.contains("/update")))
                .isEmpty();
    }

    @Test
    void testCloudFallsBackToConfiguredUrlWhenClusterStateDenied()
            throws Exception {
        var seed = node();
        seed.status = x -> x.getRequestURI().getPath().contains("/admin/")
                ? 403
                : 200;
        seed.response = OK;
        try (var c = new SolrHttpClient(config(
                SolrConnectionMode.CLOUD, seed.url("/solr/coll")))) {
            send(c);
        }
        assertThat(seed.requests)
                .anyMatch(r -> r.startsWith("POST /solr/coll/update"));
    }

    @Test
    void testCloudUnreachableDiscoveredNodeFallsBackToSeed()
            throws Exception {
        var seed = node();
        seed.response = """
            {"responseHeader":{"status":0},"cluster":{
              "collections":{"coll":{"shards":{"shard1":{"replicas":{
                "r1":{"state":"active","node_name":"h_solr",
                      "base_url":"http://localhost:1/solr"}}}}}},
              "live_nodes":["h_solr"]}}
            """;
        seed.status = x -> 200;
        try (var c = new SolrHttpClient(config(
                SolrConnectionMode.CLOUD, seed.url("/solr/coll")))) {
            send(c);
        }
        assertThat(seed.requests)
                .anyMatch(r -> r.startsWith("POST /solr/coll/update"));
    }

    @Test
    void testCloudAliasUsesLiveNodes() throws Exception {
        var seed = node();
        var port = seed.server.getAddress().getPort();
        // unknown collection (alias): fall back to live nodes
        seed.response = """
            {"responseHeader":{"status":0},"cluster":{
              "collections":{},
              "live_nodes":["localhost:%d_solr"]}}
            """.formatted(port);
        try (var c = new SolrHttpClient(config(
                SolrConnectionMode.CLOUD, seed.url("/solr/myalias")))) {
            assertThat(c.fetchCollectionUrls(seed.url("/solr/myalias")))
                    .containsExactly(seed.url("/solr/myalias"));
        }
    }

    @Test
    void testCloudAuthAppliesToClusterStatusToo() throws Exception {
        var seed = node();
        var cfg = config(SolrConnectionMode.CLOUD, seed.url("/solr/coll"));
        cfg.setBearerToken("tok");
        try (var c = new SolrHttpClient(cfg)) {
            send(c);
        }
        assertThat(seed.headers).allSatisfy(
                h -> assertThat(h).containsEntry(
                        "authorization", "Bearer tok"));
    }
}
