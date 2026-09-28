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

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.jetty.HttpJettySolrClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

    private SolrClient solrClient;

    @BeforeAll
    private void beforeAll() {
        solrClient = new HttpJettySolrClient.Builder(
                getSolrTestURL()).build();
    }

    @BeforeEach
    private void beforeEach() throws SolrServerException, IOException {
        solrClient.deleteByQuery("*:*");
        solrClient.commit();
    }

    @AfterAll
    private void afterAll() throws Exception {
        if (solrClient != null) {
            solrClient.close();
        }
    }

    public String getSolrTestURL() {
        return getSolrBaseURL() + "/test";
    }

    public String getSolrBaseURL() {
        return "http://" + solrContainer.getHost() + ":"
                + solrContainer.getSolrPort() + "/solr";
    }

    public SolrClient getSolrClient() {
        return solrClient;
    }

    protected SolrCommitter createSolrCommitter() throws CommitterException {
        var ctx = CommitterContext.builder()
                .setWorkDir(
                        new File(
                                tempDir,
                                "" + TimeIdGenerator.next()).toPath())
                .build();
        var committer = new SolrCommitter();
        committer.getConfiguration().setSolrURL(getSolrTestURL());
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
