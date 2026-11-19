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
package com.norconex.committer.solr;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.solr.SolrContainer;
import org.testcontainers.utility.DockerImageName;

import com.norconex.committer.core.CommitterContext;
import com.norconex.committer.core.CommitterException;
import com.norconex.commons.lang.TimeIdGenerator;

import tools.jackson.databind.json.JsonMapper;

/**
 * Base class for Solr tests requiring a running Solr server.  One Solr
 * instance is shared with all all tests in implementing class.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(Lifecycle.PER_CLASS)
@Timeout(30)
public abstract class AbstractSolrIT {

    private static final String SOLR_VERSION = "10.0.0";

    @SuppressWarnings("resource")
    @Container
    static SolrContainer solrContainer = new SolrContainer(
            DockerImageName.parse("solr").withTag(SOLR_VERSION))
                    .withCollection("test");

    @TempDir
    static File tempDir;

    // Independent verification of what the committer sent, over plain HTTP
    // (not the committer's own client).
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    private void beforeEach() throws IOException, InterruptedException {
        postUpdate(Map.of("delete", Map.of("query", "*:*")));
    }

    /** Adds a document directly to Solr and commits. */
    protected void addDirectly(Map<String, Object> doc)
            throws IOException, InterruptedException {
        postUpdate(Map.of("add", Map.of("doc", doc)));
    }

    /** Number of documents in Solr matching a query. */
    protected long numFound(String query)
            throws IOException, InterruptedException {
        var url = getSolrTestURL() + "/select?rows=0&q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
        var resp = http.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                BodyHandlers.ofString());
        return JsonMapper.builder().build().readTree(resp.body())
                .path("response").path("numFound").asLong(-1);
    }

    private void postUpdate(Map<String, Object> body)
            throws IOException, InterruptedException {
        var json = JsonMapper.builder().build().writeValueAsString(body);
        var resp = http.send(
                HttpRequest.newBuilder(
                        URI.create(getSolrTestURL() + "/update?commit=true"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException(
                    "Solr update failed: " + resp.statusCode()
                            + " " + resp.body());
        }
    }

    public String getSolrTestURL() {
        return getSolrBaseURL() + "/test";
    }

    public String getSolrBaseURL() {
        return "http://" + solrContainer.getHost() + ":"
                + solrContainer.getSolrPort() + "/solr";
    }

    protected SolrCommitter createSolrCommitter() throws CommitterException {
        var ctx = CommitterContext.builder()
                .setWorkDir(
                        new File(
                                tempDir,
                                "" + TimeIdGenerator.next()).toPath())
                .build();
        var committer = new SolrCommitter();
        committer.getConfiguration().setNodes(List.of(getSolrTestURL()));
        committer.getConfiguration().setUpdateUrlParam("commitWithin", "1");
        committer.init(ctx);
        return committer;
    }

    protected void withinCommitterSession(CommitterConsumer c)
            throws CommitterException {
        var committer = createSolrCommitter();
        try {
            c.accept(committer);
        } catch (CommitterException e) {
            throw e;
        } catch (Exception e) {
            throw new CommitterException(e);
        }
        committer.close();
    }

    @FunctionalInterface
    protected interface CommitterConsumer {
        void accept(SolrCommitter c) throws Exception;
    }
}
