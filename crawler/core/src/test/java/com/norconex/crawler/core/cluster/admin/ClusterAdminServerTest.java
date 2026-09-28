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
package com.norconex.crawler.core.cluster.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.CrawlerException;
import com.norconex.crawler.core.cluster.Cluster;
import com.norconex.crawler.core.cluster.ClusterConfig;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.session.CrawlerSession;

@Timeout(30)
class ClusterAdminServerTest {

    @TempDir
    Path tempDir;

    @Test
    void start_exposesEndpointsAndWritesPortFile() throws Exception {
        var fixture = newFixture(0, tempDir);
        when(fixture.cluster.getNodeCount()).thenReturn(3);
        when(fixture.cluster.getNodeNames())
                .thenReturn(List.of("node-a", "node-b"));

        var server = new ClusterAdminServer(fixture.session);
        var port = server.start();

        try {
            assertThat(port).isPositive();
            assertThat(Files.readString(
                    tempDir.resolve(ClusterAdminServer.ADMIN_PORT_FILE)))
                            .isEqualTo(String.valueOf(port));

            assertThat(send(port, "GET", Endpoint.CLUSTER_SIZE, "crawler-1")
                    .statusCode())
                            .isEqualTo(200);
            assertThat(send(port, "GET", Endpoint.CLUSTER_SIZE, "crawler-1")
                    .body())
                            .isEqualTo("3");
            assertThat(send(port, "GET", Endpoint.CLUSTER_NODES, "crawler-1")
                    .body())
                            .isEqualTo("node-a,node-b");
            assertThat(send(port, "POST", Endpoint.CLUSTER_STOP, "crawler-1")
                    .body())
                            .isEqualTo("Stopping cluster");

            verify(fixture.cluster).stop();
        } finally {
            server.close();
        }

        assertThat(tempDir.resolve(ClusterAdminServer.ADMIN_PORT_FILE))
                .doesNotExist();
    }

    @Test
    void endpoints_rejectWrongMethodAndCrawlerId() throws Exception {
        var fixture = newFixture(0, tempDir);
        var server = new ClusterAdminServer(fixture.session);
        var port = server.start();

        try {
            assertThat(send(port, "GET", Endpoint.CLUSTER_STOP, "crawler-1")
                    .statusCode())
                            .isEqualTo(405);
            assertThat(send(port, "GET", Endpoint.CLUSTER_SIZE, "wrong-id")
                    .statusCode())
                            .isEqualTo(412);
        } finally {
            server.close();
        }
    }

    @Test
    void start_usesNextAvailablePortWhenBasePortIsTaken() throws IOException {
        try (var occupied = new ServerSocket(0)) {
            var basePort = occupied.getLocalPort();
            var fixture = newFixture(basePort, tempDir);
            var server = new ClusterAdminServer(fixture.session);
            var actualPort = server.start();

            try {
                assertThat(actualPort).isNotEqualTo(basePort);
                assertThat(actualPort).isGreaterThan(basePort);
            } finally {
                server.close();
            }
        }
    }

    // The administrative endpoints can stop a crawl and are guarded only by a
    // crawler-id header, which is an identifier from the configuration and
    // not a secret. So the default must not put them on the network.
    @Test
    void start_bindsLoopbackOnlyByDefault() throws Exception {
        var fixture = newFixture(0, tempDir);
        var server = new ClusterAdminServer(fixture.session);
        var port = server.start();

        try {
            // Bound to loopback, so unreachable from any other host. This is
            // the security property, asserted on the bound address rather
            // than by probing: binding the wildcard while loopback is held
            // fails on most platforms regardless, so a probe proves nothing.
            assertThat(server.getBoundAddress().getAddress()
                    .isLoopbackAddress()).isTrue();
            assertThat(server.getBoundAddress().getAddress()
                    .isAnyLocalAddress()).isFalse();

            // Still reachable locally, so administration keeps working.
            assertThat(send(port, "GET", Endpoint.CLUSTER_SIZE, "crawler-1")
                    .statusCode())
                            .isEqualTo(200);
        } finally {
            server.close();
        }
    }

    @Test
    void start_bindsAllInterfacesWhenAsked() throws Exception {
        var fixture = newFixture(0, tempDir);
        fixture.session.getCrawlContext().getCrawlConfig().getClusterConfig()
                .setAdminBindAddress(ClusterConfig.ADMIN_BIND_ANY);
        var server = new ClusterAdminServer(fixture.session);
        var port = server.start();

        try {
            assertThat(server.getBoundAddress().getAddress()
                    .isAnyLocalAddress()).isTrue();
            assertThat(send(port, "GET", Endpoint.CLUSTER_SIZE, "crawler-1")
                    .statusCode())
                            .isEqualTo(200);
        } finally {
            server.close();
        }
    }

    @Test
    void resolveBindAddress_defaultsAndKeywords() {
        // Blank and the default keyword both mean loopback.
        assertThat(ClusterAdminServer.resolveBindAddress(null)
                .isLoopbackAddress()).isTrue();
        assertThat(ClusterAdminServer.resolveBindAddress("  ")
                .isLoopbackAddress()).isTrue();
        assertThat(ClusterAdminServer.resolveBindAddress(
                ClusterConfig.ADMIN_BIND_LOOPBACK)
                .isLoopbackAddress()).isTrue();
        // Case should not matter in a hand-edited config file.
        assertThat(ClusterAdminServer.resolveBindAddress("LoopBack")
                .isLoopbackAddress()).isTrue();

        // Null means the wildcard, i.e. every interface.
        assertThat(ClusterAdminServer.resolveBindAddress(
                ClusterConfig.ADMIN_BIND_ANY)).isNull();
        assertThat(ClusterAdminServer.resolveBindAddress("ANY")).isNull();
    }

    @Test
    void resolveBindAddress_acceptsAnExplicitAddress() {
        assertThat(ClusterAdminServer.resolveBindAddress("127.0.0.1")
                .getHostAddress()).isEqualTo("127.0.0.1");
    }

    // Failing loudly is right: the operator asked for a specific interface
    // and did not get it. Falling back silently would either disable
    // administration or expose it, and both are worse than stopping.
    @Test
    void resolveBindAddress_rejectsAnUnresolvableAddress() {
        assertThatExceptionOfType(CrawlerException.class)
                .isThrownBy(() -> ClusterAdminServer.resolveBindAddress(
                        "no-such-host.invalid"))
                .withMessageContaining(ClusterConfig.ADMIN_BIND_LOOPBACK)
                .withMessageContaining(ClusterConfig.ADMIN_BIND_ANY);
    }

    private HttpResponse<String> send(
            int port, String method, Endpoint endpoint, String crawlerId)
            throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + endpoint))
                .header("crawler-id", crawlerId);
        var request = "POST".equals(method)
                ? builder.POST(HttpRequest.BodyPublishers.noBody()).build()
                : builder.GET().build();
        return HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString());
    }

    private Fixture newFixture(int adminPort, Path workDir) {
        var session = mock(CrawlerSession.class);
        var cluster = mock(Cluster.class);
        var crawlContext = mock(CrawlerContext.class);
        var crawlConfig = new CrawlerConfig();
        var clusterConfig = new ClusterConfig();

        clusterConfig.setAdminPort(adminPort);
        crawlConfig.setClusterConfig(clusterConfig);

        when(session.getCluster()).thenReturn(cluster);
        when(session.getCrawlerId()).thenReturn("crawler-1");
        when(session.getCrawlContext()).thenReturn(crawlContext);
        when(crawlContext.getCrawlConfig()).thenReturn(crawlConfig);
        when(crawlContext.getWorkDir()).thenReturn(workDir);

        return new Fixture(session, cluster);
    }

    private record Fixture(CrawlerSession session, Cluster cluster) {
    }
}
