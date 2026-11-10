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

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.StringUtils;

import com.norconex.committer.core.CommitterException;
import com.norconex.commons.lang.encrypt.EncryptionUtil;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * <p>
 * Minimal Solr client for sending JSON updates over HTTP, using only the
 * JDK HTTP client. Supports a single node, several nodes (load balancing
 * with failover) and SolrCloud (nodes hosting the collection are discovered
 * over HTTP, without requiring access to ZooKeeper).
 * </p>
 * <p>
 * Requests failing because of a connection problem or a "busy" server
 * (HTTP 429, 502, 503 and 504) are retried on another node when there is
 * one. Failing nodes are set aside for a while before being tried again.
 * </p>
 */
@Slf4j
final class SolrHttpClient implements Closeable {

    static final Duration DEAD_NODE_RETRY_DELAY = Duration.ofSeconds(30);
    static final Duration CLUSTER_REFRESH_DELAY = Duration.ofSeconds(60);
    static final Duration CLUSTER_REFRESH_RETRY_DELAY = Duration.ofSeconds(10);
    private static final int MIN_ATTEMPTS = 3;
    private static final Set<Integer> RETRYABLE_STATUSES =
            Set.of(429, 502, 503, 504);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final HttpClient http;
    private final Duration socketTimeout;
    private final Map<String, String> headers = new LinkedHashMap<>();
    // Collection (or core) URLs given in the configuration.
    private final List<String> seedUrls;
    private final boolean cloud;
    private final AtomicInteger roundRobin = new AtomicInteger();
    // endpoint -> time (nanos) at which it can be tried again
    private final Map<String, Long> deadUntil = new ConcurrentHashMap<>();

    // Collection URLs discovered from the cluster state (cloud mode).
    private volatile List<String> discoveredUrls = List.of();
    private volatile long nextClusterRefresh;
    private volatile boolean clusterRefreshWarned;

    SolrHttpClient(SolrCommitterConfig cfg) throws CommitterException {
        var conn = resolve(cfg);
        seedUrls = conn.nodes();
        cloud = conn.mode() == SolrConnectionMode.CLOUD;

        var useHttp2 = conn.httpVersion() == SolrHttpVersion.HTTP_2
                || conn.httpVersion() == SolrHttpVersion.AUTO
                        && seedUrls.stream()
                                .allMatch(u -> u.startsWith("https:"));
        http = HttpClient.newBuilder()
                .version(useHttp2 ? Version.HTTP_2 : Version.HTTP_1_1)
                .connectTimeout(cfg.getConnectionTimeout())
                // Redirects would turn POST requests into GET.
                .followRedirects(Redirect.NEVER)
                .build();
        socketTimeout = cfg.getSocketTimeout();
        initHeaders(cfg);
    }

    /** Effective connection settings, once defaults and legacy ones apply. */
    record Connection(
            List<String> nodes,
            SolrConnectionMode mode,
            SolrHttpVersion httpVersion) {
    }

    // Visible for testing
    @SuppressWarnings("deprecation")
    static Connection resolve(SolrCommitterConfig cfg)
            throws CommitterException {

        // Nodes
        var urls = new ArrayList<String>(cfg.getNodes());
        if (StringUtils.isNotBlank(cfg.getSolrURL())) {
            if (urls.isEmpty()) {
                LOG.warn("'solrURL' is deprecated. Use 'nodes' instead.");
                Arrays.stream(cfg.getSolrURL().split("\s*,\s*"))
                        .forEach(urls::add);
            } else {
                LOG.warn("Both 'nodes' and the deprecated 'solrURL' are "
                        + "set. Ignoring 'solrURL'.");
            }
        }
        var nodes = new ArrayList<String>();
        for (String url : urls) {
            if (StringUtils.isNotBlank(url)) {
                nodes.add(StringUtils.removeEnd(url.trim(), "/"));
            }
        }
        if (nodes.isEmpty()) {
            throw new CommitterException(
                    "At least one Solr node must be specified in 'nodes'.");
        }

        // Mode and HTTP version
        var mode = cfg.getConnectionMode();
        var httpVersion = cfg.getHttpVersion();
        var legacy = cfg.getSolrClientType();
        if (legacy != null) {
            LOG.warn("'solrClientType' is deprecated. Use 'connectionMode' "
                    + "and 'httpVersion' instead ({} is {} and {}).",
                    legacy, legacy.getConnectionMode(),
                    legacy.getHttpVersion());
            if (mode == null) {
                mode = legacy.getConnectionMode();
            } else {
                LOG.warn("Both 'connectionMode' and the deprecated "
                        + "'solrClientType' are set. Ignoring "
                        + "'solrClientType' for the connection mode.");
            }
            if (httpVersion == null) {
                httpVersion = legacy.getHttpVersion();
            }
        }
        if (mode == null) {
            mode = nodes.size() > 1
                    ? SolrConnectionMode.LOAD_BALANCED
                    : SolrConnectionMode.SINGLE_NODE;
        }
        if (httpVersion == null) {
            httpVersion = SolrHttpVersion.AUTO;
        }
        if (mode == SolrConnectionMode.SINGLE_NODE && nodes.size() > 1) {
            throw new CommitterException(
                    "Connection mode SINGLE_NODE accepts only one node, but "
                            + nodes.size() + " were specified. Use "
                            + "LOAD_BALANCED or CLOUD.");
        }

        for (String url : nodes) {
            if (!StringUtils.startsWithIgnoreCase(url, "http://")
                    && !StringUtils.startsWithIgnoreCase(url, "https://")) {
                throw new CommitterException(
                        "Invalid Solr node URL: \"" + url + "\". URLs must "
                                + "start with http:// or https://."
                                + (mode == SolrConnectionMode.CLOUD
                                        ? " ZooKeeper connection strings are "
                                                + "not supported. Provide "
                                                + "Solr node URLs, each "
                                                + "ending with the "
                                                + "collection name."
                                        : ""));
            }
        }
        return new Connection(List.copyOf(nodes), mode, httpVersion);
    }

    private void initHeaders(SolrCommitterConfig cfg)
            throws CommitterException {
        var creds = cfg.getCredentials();
        if (creds.isSet()) {
            var basic = creds.getUsername() + ":"
                    + EncryptionUtil.decryptPassword(creds);
            headers.put(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(
                            basic.getBytes(StandardCharsets.UTF_8)));
        } else if (StringUtils.isNotBlank(cfg.getBearerToken())) {
            headers.put("Authorization", "Bearer " + cfg.getBearerToken());
        }
        // custom headers go last so they can override any of the above
        headers.putAll(cfg.getHeaders());
    }

    /**
     * Sends an update request. The first node accepting it ends the
     * attempts.
     * @param body JSON body
     * @param params URL query parameters
     * @throws CommitterException if the request could not be completed
     *     or Solr rejected it
     */
    void update(byte[] body, Map<String, String> params)
            throws CommitterException {
        var query = new StringBuilder("?wt=json");
        params.forEach(
                (k, v) -> query.append('&')
                        .append(enc(k))
                        .append('=')
                        .append(enc(StringUtils.defaultString(v))));

        var candidates = candidates();
        var attempts = Math.max(candidates.size(), MIN_ATTEMPTS);
        Exception lastError = null;
        for (var i = 0; i < attempts; i++) {
            if (i >= candidates.size()) {
                pause(Math.min(250L * i, 2000L));
            }
            var url = candidates.get(i % candidates.size());
            try {
                var resp = send(
                        HttpRequest.newBuilder(
                                URI.create(url + "/update" + query))
                                .header(
                                        "Content-Type",
                                        "application/json; charset=UTF-8")
                                .POST(BodyPublishers.ofByteArray(body)),
                        url);
                var status = resp.statusCode();
                if (status / 100 == 2) {
                    checkResponseStatus(resp.body());
                    deadUntil.remove(url);
                    return;
                }
                var msg = "Solr responded with HTTP " + status + " from "
                        + url + ": " + errorMessage(resp.body());
                if (!RETRYABLE_STATUSES.contains(status)) {
                    throw new CommitterException(msg);
                }
                lastError = new CommitterException(msg);
                markDead(url, msg);
            } catch (IOException e) {
                lastError = e;
                markDead(url, e.toString());
            }
        }
        if (cloud) {
            nextClusterRefresh = 0; // re-discover nodes next time
        }
        throw new CommitterException(
                "Could not send documents to Solr after " + attempts
                        + " attempts. Nodes tried: " + candidates,
                lastError);
    }

    @Override
    public void close() {
        http.close();
    }

    // Visible for testing
    List<String> candidates() {
        refreshClusterIfNeeded();

        var ordered = new LinkedHashSet<String>();
        var primary = cloud && !discoveredUrls.isEmpty()
                ? discoveredUrls
                : seedUrls;
        var start = Math.floorMod(roundRobin.getAndIncrement(), primary.size());
        for (var i = 0; i < primary.size(); i++) {
            ordered.add(primary.get((start + i) % primary.size()));
        }
        // Configured URLs as a last resort (e.g., when discovered node
        // addresses are not reachable from here).
        ordered.addAll(seedUrls);

        // nodes marked dead are tried last, never excluded
        var now = System.nanoTime();
        var alive = new ArrayList<String>();
        var dead = new ArrayList<String>();
        for (String url : ordered) {
            var until = deadUntil.get(url);
            if (until != null && now - until < 0) {
                dead.add(url);
            } else {
                alive.add(url);
            }
        }
        alive.addAll(dead);
        return alive;
    }

    private void markDead(String url, String reason) {
        LOG.warn("Solr node \"{}\" failed: {}", url, reason);
        deadUntil.put(
                url, System.nanoTime() + DEAD_NODE_RETRY_DELAY.toNanos());
    }

    private HttpResponse<String> send(HttpRequest.Builder req, String url)
            throws IOException {
        headers.forEach(req::header);
        try {
            return http.send(
                    req.timeout(socketTimeout).build(),
                    BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while sending to " + url, e);
        }
    }

    private static void pause(long millis) throws CommitterException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CommitterException(
                    "Interrupted while waiting to retry.", e);
        }
    }

    //--- Cloud node discovery -----------------------------------------------

    private synchronized void refreshClusterIfNeeded() {
        if (!cloud || System.nanoTime() - nextClusterRefresh < 0
                && nextClusterRefresh != 0) {
            return;
        }
        for (String seed : seedUrls) {
            try {
                var urls = fetchCollectionUrls(seed);
                if (!urls.isEmpty()) {
                    discoveredUrls = urls;
                    nextClusterRefresh = System.nanoTime()
                            + CLUSTER_REFRESH_DELAY.toNanos();
                    clusterRefreshWarned = false;
                    LOG.debug("Solr nodes discovered: {}", urls);
                    return;
                }
            } catch (IOException | RuntimeException e) {
                if (clusterRefreshWarned) {
                    LOG.debug("Cannot get cluster state from {}.", seed, e);
                } else {
                    LOG.warn(
                            "Cannot get cluster state from {} ({}). Will "
                                    + "use the configured URLs only.",
                            seed, e.toString());
                }
            }
        }
        clusterRefreshWarned = true;
        nextClusterRefresh = System.nanoTime()
                + CLUSTER_REFRESH_RETRY_DELAY.toNanos();
    }

    // Visible for testing
    List<String> fetchCollectionUrls(String seedUrl) throws IOException {
        var slash = seedUrl.lastIndexOf('/');
        var base = seedUrl.substring(0, slash);
        var collection = seedUrl.substring(slash + 1);
        var statusUrl =
                base + "/admin/collections?action=CLUSTERSTATUS&wt=json";

        var resp = send(
                HttpRequest.newBuilder(
                        URI.create(
                                statusUrl + "&collection=" + enc(collection)))
                        .GET(),
                seedUrl);
        if (resp.statusCode() == 400) {
            // not a collection name (e.g., an alias): get the entire cluster
            resp = send(HttpRequest.newBuilder(URI.create(statusUrl)).GET(),
                    seedUrl);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + ": "
                    + errorMessage(resp.body()));
        }

        var cluster = MAPPER.readTree(resp.body()).path("cluster");
        var liveNodes = new LinkedHashSet<String>();
        cluster.path("live_nodes").forEach(n -> liveNodes.add(n.asString()));

        var urls = new LinkedHashSet<String>();
        var scheme = seedUrl.substring(0, seedUrl.indexOf("://") + 3);
        var collNode = cluster.path("collections").path(collection);
        if (collNode.isObject()) {
            collNode.path("shards").forEach(
                    shard -> shard.path("replicas").forEach(replica -> {
                        if ("active".equals(replica.path("state").asString())
                                && liveNodes.contains(
                                        replica.path("node_name")
                                                .asString())) {
                            urls.add(
                                    StringUtils.removeEnd(
                                            replica.path("base_url")
                                                    .asString(),
                                            "/") + "/" + collection);
                        }
                    }));
        } else {
            // alias or unknown collection: use any live node, as Solr
            // routes requests to the right shards.
            for (String node : liveNodes) {
                urls.add(scheme + node.replaceFirst("_", "/") + "/"
                        + collection);
            }
        }
        return new ArrayList<>(urls);
    }

    //--- Responses ----------------------------------------------------------

    private static void checkResponseStatus(String body)
            throws CommitterException {
        JsonNode header;
        try {
            header = MAPPER.readTree(body).path("responseHeader");
        } catch (RuntimeException e) {
            throw new CommitterException(
                    "Invalid response from Solr: " + abbreviate(body), e);
        }
        if (header.isMissingNode()) {
            throw new CommitterException(
                    "No response headers obtained from Solr request. "
                            + "Response: " + abbreviate(body));
        }
        var status = Objects.toString(header.path("status").asString(), null);
        if (!"0".equals(status)) {
            throw new CommitterException(
                    "Invalid Solr response status: " + status);
        }
    }

    private static String errorMessage(String body) {
        try {
            var msg = MAPPER.readTree(body).path("error").path("msg");
            if (msg.isString()) {
                return msg.asString();
            }
        } catch (RuntimeException e) {
            // not JSON: fall through
        }
        return abbreviate(body);
    }

    private static String abbreviate(String str) {
        return StringUtils.abbreviate(StringUtils.trimToEmpty(str), 300);
    }

    private static String enc(String str) {
        return URLEncoder.encode(str, StandardCharsets.UTF_8);
    }
}
