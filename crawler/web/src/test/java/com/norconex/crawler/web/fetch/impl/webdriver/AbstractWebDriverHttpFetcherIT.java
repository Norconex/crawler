/* Copyright 2018-2026 Norconex Inc.
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
package com.norconex.crawler.web.fetch.impl.webdriver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.MediaType.HTML_UTF_8;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.Timeout;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerSettings;
import org.openqa.selenium.manager.SeleniumManager;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.commons.lang.img.MutableImage;
import com.norconex.crawler.web.WebCrawlerConfig;
import com.norconex.crawler.web.WebTestUtil;
import com.norconex.crawler.web.fetch.util.DocImageHandlerConfig.Target;
import com.norconex.crawler.web.junit.WebCrawlingTest;
import com.norconex.crawler.web.junit.WebCrawlingTestCapturer;
import com.norconex.crawler.web.mocks.MockWebsite;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@MockServerSettings
@TestInstance(Lifecycle.PER_CLASS)
@Timeout(120)
public abstract class AbstractWebDriverHttpFetcherIT {

    private static final int SNIFFER_PORT_START = 50000;
    private static final int SNIFFER_PORT_END = 50049; // 50 ports

    private static final int LARGE_CONTENT_MIN_SIZE = 3 * 1024 * 1024;
    private static final ConcurrentHashMap<WebDriverBrowser,
            Boolean> LOCAL_BROWSER_OK =
                    new ConcurrentHashMap<>();

    private WebDriverBrowser browserType;

    public AbstractWebDriverHttpFetcherIT(WebDriverBrowser browserType) {
        if (browserType != WebDriverBrowser.CHROME
                && browserType != WebDriverBrowser.FIREFOX
                && browserType != WebDriverBrowser.EDGE) {
            throw new IllegalArgumentException(
                    "Only Chrome, Firefox, and Edge are supported "
                            + "for testing.");
        }
        this.browserType = browserType;
    }

    @BeforeAll
    void beforeAll() {
        if (isLocalBrowserAvailable()) {
            LOG.info("Local {} browser detected. Tests will run against "
                    + "host browser managed by Selenium Manager.", browserType);
        } else {
            LOG.warn(
                    "Local {} browser was not detected. Tests will be skipped.",
                    browserType);
        }
    }

    @AfterAll
    void afterAll() {
        DriverSession.closeAllOpenSessions();
    }

    @AfterEach
    void afterEach() {
        DriverSession.closeAllOpenSessions();
    }

    @BeforeEach
    void beforeEach(ClientAndServer client) {
        Assumptions.assumeTrue(isLocalBrowserAvailable(),
                "Local " + browserType
                        + " browser not detected. Test skipped.");
    }

    private boolean isLocalBrowserAvailable() {
        return LOCAL_BROWSER_OK.computeIfAbsent(
                browserType,
                AbstractWebDriverHttpFetcherIT::isLocalBrowserDetectable);
    }

    static boolean isLocalBrowserDetectable(WebDriverBrowser browser) {
        // If the caller pre-installed the driver and set the corresponding
        // system property (e.g. -Dwebdriver.edge.driver=/path/msedgedriver.exe)
        // we accept that as sufficient proof the browser is available.
        if (driverSystemProperty(browser) != null) {
            return true;
        }
        try {
            var options = browser.createOptions(
                    new WebDriverLocation(null, null, null));
            SeleniumManager.getInstance().getBinaryPaths(List.of(
                    "--browser", options.getBrowserName()));
            return true;
        } catch (Throwable e) {
            LOG.info("Selenium Manager could not auto-detect a local {} "
                    + "browser for tests.", browser, e);
            return false;
        }
    }

    private static String driverSystemProperty(WebDriverBrowser browser) {
        return switch (browser) {
            case CHROME -> System.getProperty("webdriver.chrome.driver");
            case EDGE -> System.getProperty("webdriver.edge.driver");
            case FIREFOX -> System.getProperty("webdriver.gecko.driver");
            default -> null;
        };
    }

    @WebCrawlingTest
    void testFetchingJsGeneratedContent(
            ClientAndServer client, WebCrawlerConfig cfg) {
        MockWebsite.whenJsRenderedWebsite(client);

        WebTestUtil.ignoreAllIgnorables(cfg);
        cfg.setFetchers(List.of(createWebDriverHttpFetcher()));
        cfg.setMaxDepth(0);
        cfg.setStartReferences(List.of(hostUrl(client, "/index.html")));
        var mem = WebCrawlingTestCapturer.crawlAndCapture(cfg).getCommitter();

        assertThat(mem.getRequestCount()).isOne();
        assertThat(WebTestUtil.docText(mem.getUpsertRequests().get(0)))
                .contains("JavaScript-rendered!");
    }

    @WebCrawlingTest
    //    @Disabled("Does not work under GitHub actions.")
    void testTakeScreenshots(ClientAndServer client, WebCrawlerConfig cfg)
            throws IOException {

        MockWebsite.whenJsRenderedWebsite(client);

        var h = new WebDriverScreenshotHandler();
        h.getConfiguration()
                .setCssSelector("#applePicture")
                .setTargets(List.of(Target.METADATA))
                .setTargetMetaField("myimage");

        var f = createWebDriverHttpFetcher();
        f.getConfiguration().setScreenshotHandler(h);
        WebTestUtil.ignoreAllIgnorables(cfg);
        cfg.setFetchers(List.of(f));
        cfg.setMaxDepth(0);
        cfg.setStartReferences(List.of(hostUrl(client, "/apple.html")));
        var mem = WebCrawlingTestCapturer.crawlAndCapture(cfg).getCommitter();

        assertThat(mem.getUpsertCount()).isOne();

        var img = MutableImage.fromBase64String(
                mem.getUpsertRequests().get(0).getMetadata().getString(
                        "myimage"));
        assertThat(img).isNotNull();
        // Chrome, and maybe others, may resize the image to make it smaller
        // so that affects the max crop we can get. That's why we don't
        // check for exact dimension.
        assertThat(img.getWidth()).isLessThanOrEqualTo(350);
        assertThat(img.getHeight()).isLessThanOrEqualTo(350);
    }

    // Test using sniffer for capturing HTTP response headers and
    // using sniffer with large content (test case for:
    // https://github.com/Norconex/collector-http/issues/751)
    @WebCrawlingTest
    void testHttpSniffer(ClientAndServer client, WebCrawlerConfig cfg) {
        var isCi = "true".equalsIgnoreCase(System.getenv("CI"))
                || "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"));
        Assumptions.assumeFalse(isCi && browserType == WebDriverBrowser.EDGE,
                "Edge + HTTP sniffer integration is unstable in CI.");
        var path = "/sniffHeaders.html";

        // @formatter:off
        client
            .when(request(path))
            .respond(response()
                .withHeader("multiKey", "multiVal1", "multiVal2")
                .withHeader("singleKey", "singleValue")
                .withBody(MockWebsite
                    .htmlPage()
                    .body(RandomStringUtils.secure().nextAlphanumeric(
                            LARGE_CONTENT_MIN_SIZE))
                    .build()));
        // @formatter:on

        var sniffer = new HttpSniffer();
        var snifCfg = sniffer.getConfiguration();

        snifCfg.setHost("127.0.0.1");
        snifCfg.setPort(freeSnifferPort());
        // also test sniffer with large content
        snifCfg.setMaxBufferSize(6 * 1024 * 1024);
        LOG.debug("Random HTTP Sniffer proxy port: {}", snifCfg.getPort());
        var fetcher = createWebDriverHttpFetcher();
        fetcher.getConfiguration().setHttpSniffer(sniffer);
        WebTestUtil.ignoreAllIgnorables(cfg);
        cfg.setFetchers(List.of(fetcher));
        cfg.setMaxDepth(0);
        cfg.setStartReferences(List.of(hostUrl(client, path)));
        var mem = WebCrawlingTestCapturer.crawlAndCapture(cfg).getCommitter();

        assertThat(mem.getUpsertRequests()).hasSize(1);
        var meta = mem.getUpsertRequests().get(0).getMetadata();
        assertThat(meta.getStrings("multiKey")).containsExactly(
                "multiVal1", "multiVal2");
        assertThat(meta.getString("singleKey")).isEqualTo("singleValue");

        assertThat(WebTestUtil.docText(mem.getUpsertRequests().get(0)).length())
                .isGreaterThanOrEqualTo(LARGE_CONTENT_MIN_SIZE);
    }

    @WebCrawlingTest
    void testPageScript(ClientAndServer client, WebCrawlerConfig cfg) {
        var path = "/pageScript.html";

        // @formatter:off
        client
            .when(request(path))
            .respond(response()
                .withBody(MockWebsite
                    .htmlPage()
                    .body("""
                          <h1>Page Script Test</h1>
                          <p>H1 above should be replaced.</p>
                          """)
                    .build(),
                    HTML_UTF_8));
        // @formatter:on

        var f = createWebDriverHttpFetcher();
        f.getConfiguration().setEarlyPageScript(
                "document.title='Awesome!';");
        f.getConfiguration().setLatePageScript("""
                const els = document.getElementsByTagName('h1');
                if (els.length > 0) {
                    els[0].innerHTML='Melon';
                }
                """);
        WebTestUtil.ignoreAllIgnorables(cfg);
        cfg.setFetchers(List.of(f));
        cfg.setMaxDepth(0);
        cfg.setStartReferences(List.of(hostUrl(client, path)));
        var mem = WebCrawlingTestCapturer.crawlAndCapture(cfg).getCommitter();

        assertThat(mem.getUpsertCount()).isNotZero();
        var doc = mem.getUpsertRequests().get(0);
        assertThat(doc.getMetadata()
                .getString("dc:title"))
                        .isEqualTo("Awesome!");
        assertThat(WebTestUtil.docText(doc)).contains("Melon");
    }

    @WebCrawlingTest
    void testResolvingUserAgent(
            ClientAndServer client, WebCrawlerConfig cfg) {
        var path = "/userAgent.html";

        // @formatter:off
        client
            .when(request(path))
            .respond(response()
                .withBody(MockWebsite
                    .htmlPage()
                    .body("<p>Should grab user agent from browser.</p>")
                    .build(),
                    HTML_UTF_8));
        // @formatter:on

        var fetcher = createWebDriverHttpFetcher();
        cfg.setFetchers(List.of(fetcher));
        cfg.setMaxDepth(0);
        WebTestUtil.ignoreAllIgnorables(cfg);
        // test setting a bunch of other params
        fetcher.getConfiguration()
                .setWindowSize(new java.awt.Dimension(640, 480))
                .setPageLoadTimeout(Duration.ofSeconds(10))
                .setImplicitlyWait(Duration.ofSeconds(1))
                .setScriptTimeout(Duration.ofSeconds(10))
                .setWaitForElementSelector("p")
                .setWaitForElementTimeout(Duration.ofSeconds(10));
        cfg.setStartReferences(List.of(hostUrl(client, path)));
        WebCrawlingTestCapturer.crawlAndCapture(cfg);
        assertThat(fetcher.getUserAgent()).isNotBlank();
    }

    //--- Private/Protected ----------------------------------------------------

    private WebDriverFetcher createWebDriverHttpFetcher() {
        return Configurable.configure(new WebDriverFetcher(), cfg -> {
            cfg.setBrowser(browserType);
            var driverPath = driverSystemProperty(browserType);
            if (driverPath != null) {
                cfg.setDriverPath(java.nio.file.Path.of(driverPath));
            }
            switch (browserType) {
                case CHROME ->
                        cfg.getArguments().addAll(
                                WebDriverTestUtil.chromeTestArguments());
                case EDGE ->
                        cfg.getArguments().addAll(
                                WebDriverTestUtil.edgeTestArguments());
                case FIREFOX ->
                        cfg.getArguments().addAll(
                                WebDriverTestUtil.firefoxTestArguments());
                default -> {
                }
            }
        });
    }

    private String hostUrl(ClientAndServer client, String path) {
        return "http://%s:%s%s".formatted(
                "127.0.0.1", client.getLocalPort(), path);
    }

    private int freeSnifferPort() {
        for (var port = SNIFFER_PORT_START; port <= SNIFFER_PORT_END; port++) {
            try (var serverSocket = new ServerSocket(port)) {
                return port;
            } catch (IOException e) {
                // Port is in use, try next
            }
        }
        throw new IllegalStateException(
                "No free sniffer port available in range.");
    }
}
