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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import com.norconex.committer.core.CommitterContext;
import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.commons.lang.TimeIdGenerator;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.security.Credentials;

/**
 * Tests against real Solr servers in SolrCloud mode and with basic
 * authentication enabled.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(300)
class SolrCloudAndAuthIT {

    private static final String SOLR_IMAGE = "solr:10.0.0";
    private static final Duration STARTUP = Duration.ofMinutes(4);

    @TempDir
    File tempDir;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void testSolrCloud() throws Exception {
        try (var solr = new GenericContainer<>(SOLR_IMAGE)
                .withExposedPorts(8983)
                .withEnv("SOLR_MODE", "solrcloud")
                .waitingFor(
                        Wait.forHttp("/solr/admin/info/system")
                                .forPort(8983)
                                .withStartupTimeout(STARTUP))) {
            solr.start();
            var base = baseUrl(solr);

            var create = get(
                    base + "/admin/collections?action=CREATE&name=cloudtest"
                            + "&numShards=1&replicationFactor=1&wt=json",
                    null);
            assertThat(create).contains("\"status\":0");

            var coll = base + "/cloudtest";
            var committer = committer(SolrConnectionMode.CLOUD, coll, null);
            try {
                committer.upsert(upsert("1", "Hello"));
                committer.upsert(upsert("2", "World"));
            } finally {
                committer.close();
            }
            assertThat(numFound(coll, null)).isEqualTo(2);

            // discovery found the collection nodes (their addresses
            // may not be reachable from the host: that must not matter)
            var client = new SolrHttpClient(
                    committer.getConfiguration());
            try {
                assertThat(
                        client.fetchCollectionUrls(coll)).isNotEmpty()
                                .allMatch(u -> u.endsWith("/solr/cloudtest"));
            } finally {
                client.close();
            }
        }
    }

    @Test
    void testBasicAuthentication() throws Exception {
        try (var solr = new GenericContainer<>(SOLR_IMAGE)
                .withExposedPorts(8983)
                // security.json must be copied by the solr user, else the
                // data directory is owned by root and Solr cannot write.
                .withCommand(
                        "bash", "-c",
                        "mkdir -p /var/solr/data"
                                + " && cp /tmp/security.json /var/solr/data/"
                                + " && exec solr-precreate authtest")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource(
                                "solr-auth/security.json"),
                        "/tmp/security.json")
                .waitingFor(
                        Wait.forHttp("/solr/admin/info/system")
                                .forPort(8983)
                                .forStatusCode(401)
                                .withStartupTimeout(STARTUP))) {
            solr.start();
            var coll = baseUrl(solr) + "/authtest";
            var creds = new Credentials();
            creds.setUsername("solr");
            creds.setPassword("SolrRocks");

            // no credentials: rejected
            var noAuth = committer(SolrConnectionMode.SINGLE_NODE, coll, null);
            try {
                assertThatExceptionOfType(CommitterException.class)
                        .isThrownBy(() -> {
                            noAuth.upsert(upsert("1", "Hello"));
                            noAuth.close();
                        })
                        .withStackTraceContaining("HTTP 401");
            } finally {
                noAuth.close();
            }

            // wrong credentials: rejected
            var bad = new Credentials();
            bad.setUsername("solr");
            bad.setPassword("nope");
            var badAuth = committer(SolrConnectionMode.SINGLE_NODE, coll, bad);
            try {
                assertThatExceptionOfType(CommitterException.class)
                        .isThrownBy(() -> {
                            badAuth.upsert(upsert("1", "Hello"));
                            badAuth.close();
                        })
                        .withStackTraceContaining("HTTP 401");
            } finally {
                badAuth.close();
            }

            // good credentials
            var committer =
                    committer(SolrConnectionMode.SINGLE_NODE, coll, creds);
            try {
                committer.upsert(upsert("1", "Hello"));
            } finally {
                committer.close();
            }
            var basic = "Basic " + Base64.getEncoder()
                    .encodeToString("solr:SolrRocks".getBytes(UTF_8));
            assertThat(numFound(coll, basic)).isEqualTo(1);
        }
    }

    private static String baseUrl(GenericContainer<?> solr) {
        return "http://" + solr.getHost() + ":" + solr.getMappedPort(8983)
                + "/solr";
    }

    private SolrCommitter committer(
            SolrConnectionMode mode, String url, Credentials creds)
            throws CommitterException {
        var committer = new SolrCommitter();
        var cfg = committer.getConfiguration();
        cfg.setConnectionMode(mode);
        cfg.setNodes(java.util.List.of(url));
        if (creds != null) {
            cfg.setCredentials(creds);
        }
        committer.init(
                CommitterContext.builder()
                        .setWorkDir(
                                new File(tempDir, "" + TimeIdGenerator.next())
                                        .toPath())
                        .build());
        return committer;
    }

    private static UpsertRequest upsert(String id, String content) {
        var p = new Properties();
        p.add("id", id);
        return new UpsertRequest(
                id, p,
                new java.io.ByteArrayInputStream(content.getBytes(UTF_8)));
    }

    private int numFound(String collUrl, String authorization)
            throws IOException, InterruptedException {
        var body = get(
                collUrl + "/select?wt=json&q="
                        + URLEncoder.encode("*:*", UTF_8),
                authorization);
        var m = Pattern.compile("\"numFound\":(\\d+)").matcher(body);
        assertThat(m.find()).as(body).isTrue();
        return Integer.parseInt(m.group(1));
    }

    private String get(String url, String authorization)
            throws IOException, InterruptedException {
        var req = HttpRequest.newBuilder(URI.create(url)).GET();
        if (authorization != null) {
            req.header("Authorization", authorization);
        }
        return http.send(req.build(), BodyHandlers.ofString()).body();
    }
}
