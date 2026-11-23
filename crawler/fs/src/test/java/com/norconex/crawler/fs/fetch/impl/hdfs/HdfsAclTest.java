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
package com.norconex.crawler.fs.fetch.impl.hdfs;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.committer.core.acl.AclCodec;
import com.norconex.committer.core.acl.AclEntry;
import com.norconex.committer.core.acl.AclKind;
import com.norconex.committer.core.acl.AclLevel;
import com.norconex.committer.core.acl.AclScope;
import com.norconex.committer.core.acl.AclStatus;
import com.norconex.commons.lang.map.Properties;
import com.norconex.crawler.fs.fetch.acl.AclFetchMode;
import com.norconex.crawler.fs.fetch.acl.PosixAcl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

@Timeout(30)
class HdfsAclTest {

    private static final String DIR = "hdfs:localhost";

    // path -> [owner, group, permission, aclBit, acl entries (JSON array)]
    private static final Map<String, String[]> STATUSES = Map.of(
            "/", new String[] { "hdfs", "supergroup", "755", "false", "" },
            "/data", new String[] {
                    "hdfs", "analysts", "750", "true",
                    "\"user:auditor:r-x\",\"group::r-x\"" },
            "/data/report.csv", new String[] {
                    "carla", "analysts", "640", "true",
                    "\"user:bob:r--\",\"group::---\",\"group:sales:r--\","
                            + "\"default:user:zed:rwx\"" });

    private HttpServer server;
    private int port;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/webhdfs/v1", this::handle);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var path = exchange.getRequestURI().getPath()
                    .substring("/webhdfs/v1".length());
            if (path.isEmpty()) {
                path = "/";
            }
            var query = exchange.getRequestURI().getQuery();
            var s = STATUSES.get(path);
            String json;
            if (s == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (query.contains("op=GETACLSTATUS")) {
                json = "{\"AclStatus\":{\"entries\":[" + s[4]
                        + "],\"owner\":\"" + s[0] + "\",\"group\":\"" + s[1]
                        + "\",\"permission\":\"" + s[2] + "\"}}";
            } else {
                json = "{\"FileStatus\":{\"type\":\""
                        + (path.endsWith(".csv") ? "FILE" : "DIRECTORY")
                        + "\",\"length\":3,\"modificationTime\":1,"
                        + "\"accessTime\":1,\"owner\":\"" + s[0]
                        + "\",\"group\":\"" + s[1] + "\",\"permission\":\""
                        + s[2] + "\",\"aclBit\":" + s[3] + "}}";
            }
            var body = json.getBytes(UTF_8);
            exchange.getResponseHeaders().set(
                    "Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private Properties fetchAcl(AclFetchMode mode) throws IOException {
        var fetcher = new HdfsFetcher();
        fetcher.getConfiguration().setAcl(mode);
        var path = (HdfsPath) fetcher.resolvePath(
                "webhdfs://localhost:" + port + "/data/report.csv");
        var meta = new Properties();
        fetcher.fetchAcl(path,
                Files.readAttributes(path, HdfsFileAttributes.class), meta);
        return meta;
    }

    @Test
    void testExtended() throws IOException {
        var acl = AclCodec.read(fetchAcl(AclFetchMode.EXTENDED)).orElseThrow();

        assertThat(acl.status()).isEqualTo(AclStatus.COMPLETE);
        assertThat(acl.levels()).extracting(AclLevel::name)
                .containsExactly("path", "file");
        // "/" lets everybody through; /data its owner, auditor and group
        // (750 with an ACL: the group bits are the mask, r-x)
        assertThat(acl.levels().get(0).entries()).containsExactlyInAnyOrder(
                AclEntry.allow(AclKind.USER, DIR, "hdfs"),
                AclEntry.allow(AclKind.USER, DIR, "auditor"),
                AclEntry.allow(AclKind.GROUP, DIR, "analysts"));
        // 640 with an ACL: the mask is r--; the owning group itself has ---
        assertThat(acl.levels().get(1).entries()).containsExactlyInAnyOrder(
                AclEntry.allow(AclKind.USER, DIR, "carla"),
                AclEntry.allow(AclKind.USER, DIR, "bob"),
                AclEntry.allow(AclKind.GROUP, DIR, "sales"));
        assertThat(acl.owners()).containsExactly(
                AclEntry.owner(AclKind.USER, DIR, "carla"));
        assertThat(acl.nativeData().get("extended").toString())
                .contains("default:user:zed:rwx");
    }

    @Test
    void testBasic() throws IOException {
        var acl = AclCodec.read(fetchAcl(AclFetchMode.BASIC)).orElseThrow();

        assertThat(acl.scope()).isEqualTo(AclScope.BASIC);
        // mode bits only: 640 reads as the group being allowed
        assertThat(acl.levels().getLast().entries()).containsExactlyInAnyOrder(
                AclEntry.allow(AclKind.USER, DIR, "carla"),
                AclEntry.allow(AclKind.GROUP, DIR, "analysts"));
    }

    @Test
    void testApplyAclEntriesWithMaskEntry() {
        var acl = new PosixAcl(DIR, null, null, 0640);
        HdfsFetcher.applyAclEntries(acl,
                List.of("user:bob:rw-", "mask::-w-", "other::r--"), 0640);
        // bob's read is masked out; others may read
        assertThat(acl.level("file", PosixAcl.READ).entries())
                .contains(AclEntry.deny(AclKind.USER, DIR, "bob"),
                        AclEntry.allowSpecial(AclEntry.SPECIAL_EVERYONE));
    }

    @Test
    void testFileStatusAttributes() throws IOException {
        var fetcher = new HdfsFetcher();
        var path = fetcher.resolvePath(
                "webhdfs://localhost:" + port + "/data");
        var attrs = Files.readAttributes(path, HdfsFileAttributes.class);
        assertThat(attrs.owner()).isEqualTo("hdfs");
        assertThat(attrs.group()).isEqualTo("analysts");
        assertThat(attrs.permission()).isEqualTo("750");
        assertThat(attrs.aclBit()).isTrue();
        assertThat(URI.create(path.toUri().toString()).getPath())
                .isEqualTo("/data");
    }
}
