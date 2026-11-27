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
package com.norconex.committer.idol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerSettings;

import com.norconex.committer.core.CommitterContext;
import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.committer.core.acl.Acl;
import com.norconex.committer.core.acl.AclCodec;
import com.norconex.committer.core.acl.AclEntry;
import com.norconex.committer.core.acl.AclFormat;
import com.norconex.committer.core.acl.AclKind;
import com.norconex.committer.core.acl.AclLevel;
import com.norconex.commons.lang.map.Properties;

/**
 * IDOL has access control of its own: the permissions are written the way
 * its mapped security reads them, unless asked for as JSON.
 */
@Isolated
@MockServerSettings
@TestInstance(Lifecycle.PER_CLASS)
@Timeout(30)
class IdolNativeAclTest {

    @TempDir
    static File tempDir;

    private static ClientAndServer mockIdol;

    @BeforeAll
    void beforeAll(ClientAndServer client) {
        mockIdol = client;
    }

    @AfterEach
    void afterEach() {
        mockIdol.reset();
    }

    private static Acl acl() {
        return Acl.complete(List.of(new AclLevel("file", List.of(
                AclEntry.allow(AclKind.USER, null, "pat"),
                AclEntry.allow(AclKind.GROUP, null, "sales")))), List.of(),
                null);
    }

    /** Sends one document with permissions and returns what IDOL received. */
    private String send(Consumer<IdolCommitter> setup)
            throws CommitterException {
        mockIdol.when(request().withPath("/DREADDDATA"))
                .respond(response().withBody("INDEXID=1"));
        var committer = new IdolCommitter();
        committer.getConfiguration().setUrl(
                "http://localhost:" + mockIdol.getLocalPort());
        setup.accept(committer);
        committer.init(CommitterContext.builder()
                .setWorkDir(new File(tempDir, "w" + System.nanoTime())
                        .toPath())
                .build());
        var meta = new Properties();
        meta.add("title", "Q3");
        AclCodec.write(meta, acl());
        committer.upsert(new UpsertRequest("http://doc/1", meta, null));
        committer.close();
        return mockIdol.retrieveRecordedRequests(
                request().withPath("/DREADDDATA"))[0].getBodyAsString();
    }

    @Test
    void permissionsAreWrittenInTheMappedSecurityField()
            throws CommitterException {
        var body = send(c -> c.getConfiguration()
                .setSecurityType("OPENTEXT"));

        assertThat(body)
                .contains("#DREFIELD AUTONOMYMETADATA="
                        + "\"0:U:pat:G:sales:NU::NG:\"")
                .contains("#DREFIELD SECURITYTYPE=\"OPENTEXT\"")
                .doesNotContain("crawler.acl");
    }

    @Test
    void theFieldAndTheSyntaxCanBeChosen() throws CommitterException {
        var body = send(c -> c.getConfiguration()
                .setAclField("ACL").setAclSyntax(IdolAclSyntax.SIMPLE));

        assertThat(body).contains("#DREFIELD ACL=\":U:pat:G:sales\"")
                .doesNotContain("SECURITYTYPE");
    }

    @Test
    void jsonSendsTheFieldAsItIs() throws CommitterException {
        var body = send(c -> c.getConfiguration().getAcl()
                .setFormat(AclFormat.JSON));

        assertThat(body).contains("crawler.acl")
                .doesNotContain("AUTONOMYMETADATA");
    }

    @Test
    void aConnectorFrameworkServerGetsJsonByDefault() {
        var committer = new IdolCommitter();
        committer.getConfiguration().setCfs(true);
        assertThat(committer.supportsNativeAcl()).isFalse();
        committer.getConfiguration().setCfs(false);
        assertThat(committer.supportsNativeAcl()).isTrue();
    }
}
