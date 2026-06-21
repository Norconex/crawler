/* Copyright 2021-2026 Norconex Inc.
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
package com.norconex.crawler.web.cases.feature;

import static com.norconex.crawler.web.mocks.MockWebsite.secureServerUrl;
import static com.norconex.crawler.web.mocks.MockWebsite.serverUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerSettings;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.MediaType;

import com.hazelcast.core.Hazelcast;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.crawler.web.WebTestUtil;
import com.norconex.crawler.web.doc.operations.scope.impl.GenericUrlScopeResolver;
import com.norconex.crawler.web.fetch.util.HstsResolver;
import com.norconex.crawler.web.junit.WebCrawlingTestCapturer;
import com.norconex.crawler.web.stubs.CrawlerConfigStubs;

/**
 * Tests that a page will force fetching https when HSTS support is
 * in place for a site.
 */
//Related to https://github.com/Norconex/collector-http/issues/694
@MockServerSettings
@Timeout(180)
@Isolated
class StrictTransportSecurityIT {

    @TempDir
    private Path tempDir;

    @BeforeEach
    void setUp() {
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private void cleanup() {
        Hazelcast.shutdownAll();
        HstsResolver.clearCache();
        try {
            //NOSONAR Give Hazelcast time to fully shut down.
            Thread.sleep(2000); //NOSONAR
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Waits until the MockServer responds with expectations for the given
     * path, or times out after 2 seconds.
     */
    private void waitForMockServerReady(ClientAndServer client, String path) {
        var deadline = System.currentTimeMillis() + 10000;
        var ready = false;
        Exception last = null;
        while (System.currentTimeMillis() < deadline && !ready) {
            try {
                // Retrieve all expectations and check path manually to avoid
                // issues with matcher strictness regarding the 'secure' flag.
                var expectations = client.retrieveActiveExpectations(null);
                if (expectations != null) {
                    for (var expectation : expectations) {
                        if (expectation
                                .getHttpRequest() instanceof HttpRequest req
                                && path.equals(req.getPath().getValue())) {
                            ready = true;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                last = e;
            }
            if (ready) {
                break;
            }
            try {
                Thread.sleep(200); //NOSONAR
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!ready) {
            throw new RuntimeException("MockServer not ready for path: " + path,
                    last);
        }
    }

    @Test
    void testClientYesServerNo(ClientAndServer client) {
        runStrictTransportSecurityScenario(true, false, false, client);
    }

    @Test
    void testClientYesServerYes(ClientAndServer client) {
        runStrictTransportSecurityScenario(true, true, true, client);
    }

    @Test
    void testClientNoServerNo(ClientAndServer client) {
        runStrictTransportSecurityScenario(false, false, false, client);
    }

    @Test
    void testClientNoServerYes(ClientAndServer client) {
        runStrictTransportSecurityScenario(false, true, false, client);
    }

    private void runStrictTransportSecurityScenario(
            boolean clientSupportsHSTS,
            boolean serverSupportsHSTS,
            boolean expectsSecureUrl,
            ClientAndServer client) {

        client.reset();
        HstsResolver.clearCache();

        System.out.println(">>> testStrictTransportSecurity: clientHSTS="
                + clientSupportsHSTS
                + ", serverHSTS=" + serverSupportsHSTS + ", expectsSecure="
                + expectsSecureUrl);

        var basePath = "/strictTransportSecurity";
        var securePath = basePath + "/secure.html";
        var secureUrl = secureServerUrl(client, securePath);
        var securablePath = basePath + "/securable.html";

        // We MUST use the secure port for the securable URL link when we expect
        // an upgrade. Otherwise, HSTS protocol upgrade will point to the
        // non-secure port where no SSL listener exists.
        var securableUrl = expectsSecureUrl
                ? secureServerUrl(client, securablePath).replace("https://",
                        "http://")
                : serverUrl(client, securablePath);

        HstsResolver.clearCache();
        // @formatter:off
        if (serverSupportsHSTS) {
            client
                .when(request().withMethod("HEAD"))
                .respond(response()
                    .withHeader(
                            "Strict-Transport-Security",
                            "max-age=16070400; includeSubDomains"));
        } else {
            client
                .when(request().withMethod("HEAD"))
                .respond(response());
        }
        var secureResponse = response()
            .withBody(
                    "Will this <a href=\"%s\">link</a> be secure?"
                            .formatted(securableUrl),
                    MediaType.HTML_UTF_8);
        if (serverSupportsHSTS) {
            secureResponse.withHeader(
                    "Strict-Transport-Security",
                    "max-age=16070400; includeSubDomains");
        }
        client
            .when(request(securePath).withSecure(true))
            .respond(secureResponse);

        client
            .when(request(securablePath).withSecure(true))
            .respond(response()
                .withBody("I am secure"));
        client
            .when(request(securablePath).withSecure(false))
            .respond(response()
                .withBody("I am NOT secure"));
        // @formatter:on

        // Wait for MockServer to register all expectations before crawling
        waitForMockServerReady(client, securePath);
        waitForMockServerReady(client, securablePath);

        var cfg = CrawlerConfigStubs.memoryCrawlerConfig(tempDir);
        cfg.setId("test-hsts-"
                + clientSupportsHSTS + '-'
                + serverSupportsHSTS + '-'
                + expectsSecureUrl);

        // Allow port and protocol changes as we switch between http and https.
        // often on different ports in mock tests.
        var scopeCfg = ((GenericUrlScopeResolver) cfg.getUrlScopeResolver())
                .getConfiguration();
        scopeCfg.setStayOnProtocol(false);
        scopeCfg.setStayOnPort(false);

        cfg.setStartReferences(List.of(secureUrl));
        cfg.setMaxDocuments(5);
        cfg.setMaxDepth(5);
        cfg.setNumThreads(1);
        var fetcherCfg = WebTestUtil.firstHttpFetcherConfig(cfg);
        fetcherCfg.setTrustAllSSLCertificates(true);

        // Diagnostic: Listen for rejection or fetch errors to see why the
        // URL is missing
        cfg.addEventListeners(List.of(event -> {
            if (event.getName().contains("REJECTED")
                    || event.getName().contains("ERROR")
                    || event.getName().contains("FETCHED")) {
                System.out.println("    [DIAGNOSTIC] " + event.getName()
                        + " - " + event);
            }
        }));

        if (!clientSupportsHSTS) {
            fetcherCfg.setHstsDisabled(true);
        }

        var expectedUrl = securableUrl;
        if (expectsSecureUrl) {
            expectedUrl = expectedUrl.replace("http://", "https://");
        }

        try {
            var mem =
                    WebCrawlingTestCapturer.crawlAndCapture(cfg).getCommitter();

            assertThat(mem.getUpsertRequests())
                    .map(UpsertRequest::getReference)
                    .filteredOn(ref -> !ref.equals(secureUrl))
                    .containsExactly(expectedUrl);
        } catch (Throwable t) {
            System.err.println("FAILED variant details:");
            System.err.println("  Start URL:    " + secureUrl);
            System.err.println("  Expected URL: " + expectedUrl);
            System.err.println(
                    "  MockServer logs:\n" + client.retrieveLogMessages(null));
            throw t;
        }
    }
}
