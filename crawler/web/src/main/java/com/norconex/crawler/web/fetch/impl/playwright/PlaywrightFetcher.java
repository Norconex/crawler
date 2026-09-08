/* Copyright 2024-2026 Norconex Inc.
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
package com.norconex.crawler.web.fetch.impl.playwright;

import static java.util.Optional.ofNullable;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.core5.http.HttpHeaders;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType.LaunchOptions;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.Proxy;
import com.microsoft.playwright.options.WaitUntilState;
import com.norconex.commons.lang.file.ContentType;
import com.norconex.crawler.core.fetch.AbstractFetcher;
import com.norconex.crawler.core.fetch.FetchException;
import com.norconex.crawler.core.fetch.FetchRequest;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.web.doc.WebDocMetadata;
import com.norconex.crawler.web.fetch.HttpMethod;
import com.norconex.crawler.web.fetch.WebFetchRequest;
import com.norconex.crawler.web.fetch.WebFetchResponse;
import com.norconex.crawler.web.fetch.impl.httpclient.HttpClientFetchResponse;
import com.norconex.crawler.web.fetch.impl.playwright.PlaywrightFetcherConfig.WaitElementType;
import com.norconex.crawler.web.fetch.util.ApacheHttpUtil;
import com.norconex.importer.doc.Doc;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Uses <a href="https://playwright.dev/java/">Microsoft Playwright</a>
 * for browser-based crawling. Playwright drives real browsers (Chromium,
 * Firefox, WebKit) across Windows, macOS, and Linux without requiring an
 * external WebDriver binary — browser binaries are downloaded automatically
 * by Playwright on first use.
 * </p>
 *
 * <h2>Key advantages over {@code WebDriverFetcher}</h2>
 * <ul>
 *   <li>No external driver binary needed.</li>
 *   <li>HTTP response headers (including status codes) are captured natively
 *       via Playwright's response interception — no proxy sniffer is
 *       required.</li>
 *   <li>Supports three cross-platform engines: Chromium, Firefox, and
 *       WebKit.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>
 * Playwright is <b>not</b> thread-safe. Each crawl thread gets its own
 * {@link Playwright} and {@link Browser} instance managed via
 * {@link ThreadLocal}. All instances are tracked for clean shutdown.
 * </p>
 *
 * <h2>Supported HTTP method</h2>
 * <p>
 * This fetcher only supports HTTP GET method.
 * </p>
 *
 * <h2>HTTP Headers</h2>
 * <p>
 * HTTP response headers (including the real status code and reason phrase)
 * are captured via Playwright's {@code page.onResponse()} listener and
 * stored in the document metadata.
 * </p>
 *
 * @see PlaywrightFetcherConfig
 * @since 4.0.0
 */
@Slf4j
@EqualsAndHashCode
@ToString
public class PlaywrightFetcher
        extends AbstractFetcher<PlaywrightFetcherConfig> {

    // Static set tracks ALL Playwright instances across all fetcher instances
    // so that a JVM shutdown hook can close them on abnormal exit (SIGKILL,
    // OOM, test cancel), mirroring DriverSession's safety net.
    private static final Set<Playwright> ALL_PLAYWRIGHT_INSTANCES =
            ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(
                PlaywrightFetcher::closeAllPlaywrightInstances,
                "PlaywrightFetcher-ShutdownHook"));
    }

    private static void closeAllPlaywrightInstances() {
        for (var pw : ALL_PLAYWRIGHT_INSTANCES) {
            try {
                pw.close();
            } catch (Exception e) {
                LOG.warn("Shutdown hook: error closing Playwright instance.",
                        e);
            }
        }
        ALL_PLAYWRIGHT_INSTANCES.clear();
    }

    @Getter
    private final PlaywrightFetcherConfig configuration =
            new PlaywrightFetcherConfig();

    // Per-thread Playwright instance (not thread-safe)
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    final ThreadLocal<Playwright> playwrightLocal = new ThreadLocal<>();

    // Per-thread Browser instance
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    final ThreadLocal<Browser> browserLocal = new ThreadLocal<>();

    // Per-thread navigation counter for browserMaxNavigations
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    final ThreadLocal<AtomicInteger> navCountLocal =
            ThreadLocal.withInitial(AtomicInteger::new);

    // Per-thread browser start time for browserMaxAge.
    // Package-private, like browserLocal and navCountLocal above, so a test
    // can age a browser deliberately instead of waiting for the wall clock.
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    final ThreadLocal<Instant> browserStartTimeLocal =
            new ThreadLocal<>();

    // All Playwright instances created — tracked for shutdown
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    final CopyOnWriteArrayList<Playwright> allPlaywrights =
            new CopyOnWriteArrayList<>();

    @Override
    protected void fetcherStartup(CrawlerSession c) {
        LOG.info("Starting Playwright fetcher ({} browser)...",
                configuration.getBrowser());
    }

    @Override
    protected void fetcherShutdown(CrawlerSession c) {
        LOG.info("Shutting down Playwright fetcher...");
        for (var pw : allPlaywrights) {
            try {
                pw.close();
            } catch (Exception e) {
                LOG.warn("Error closing Playwright instance during shutdown.",
                        e);
            } finally {
                ALL_PLAYWRIGHT_INSTANCES.remove(pw);
            }
        }
        allPlaywrights.clear();
        // Clean up ThreadLocals for the calling thread
        playwrightLocal.remove();
        browserLocal.remove();
        navCountLocal.remove();
        browserStartTimeLocal.remove();
    }

    @Override
    protected boolean acceptRequest(@NonNull FetchRequest req) {
        return HttpMethod.GET.is(((WebFetchRequest) req).getMethod());
    }

    @Override
    public WebFetchResponse fetch(FetchRequest req) throws FetchException {
        var method = ofNullable(
                ((WebFetchRequest) req).getMethod()).orElse(HttpMethod.GET);
        var doc = req.getDoc();

        if (method != HttpMethod.GET) {
            var reason = "HTTP " + method + " method not supported.";
            if (method == HttpMethod.HEAD) {
                reason +=
                        " To obtain headers, use GET with PlaywrightFetcher.";
            }
            return HttpClientFetchResponse.builder()
                    .processingOutcome(ProcessingOutcome.UNSUPPORTED)
                    .reasonPhrase(reason)
                    .statusCode(-1)
                    .build();
        }

        LOG.debug("Fetching document: {}", doc.getReference());

        var browser = getOrCreateBrowser();
        return fetchWithBrowser(browser, doc);
    }

    //--- Internal helpers ---------------------------------------------------

    /**
     * Returns the thread-local {@link Browser}, creating (or re-creating)
     * it when necessary based on navigation count and age limits.
     */
    Browser getOrCreateBrowser() {
        var existing = browserLocal.get();
        if (existing != null && shouldRecycleBrowser()) {
            LOG.debug("Recycling browser instance for thread {}.",
                    Thread.currentThread().getName());
            try {
                existing.close();
            } catch (Exception e) {
                LOG.warn("Error closing browser before recycle.", e);
            }
            browserLocal.remove();
            browserStartTimeLocal.remove();
            existing = null;
        }

        if (existing == null || !existing.isConnected()) {
            var pw = playwrightLocal.get();
            if (pw == null) {
                pw = Playwright.create();
                playwrightLocal.set(pw);
                allPlaywrights.add(pw);
                ALL_PLAYWRIGHT_INSTANCES.add(pw);
                LOG.debug("Created new Playwright instance for thread {}.",
                        Thread.currentThread().getName());
            }
            var newBrowser = createBrowser(pw);
            browserLocal.set(newBrowser);
            navCountLocal.get().set(0);
            browserStartTimeLocal.set(Instant.now());
            return newBrowser;
        }
        return existing;
    }

    private boolean shouldRecycleBrowser() {
        var maxNav = configuration.getBrowserMaxNavigations();
        if (maxNav > 0) {
            var count = navCountLocal.get();
            if (count != null && count.get() >= maxNav) {
                return true;
            }
        }
        var maxAge = configuration.getBrowserMaxAge();
        if (maxAge != null) {
            var startTime = browserStartTimeLocal.get();
            if (startTime != null
                    && Instant.now().isAfter(startTime.plus(maxAge))) {
                return true;
            }
        }
        return false;
    }

    private Browser createBrowser(Playwright pw) {
        var cfg = configuration;
        var opts = new LaunchOptions()
                .setHeadless(cfg.isHeadless());

        if (StringUtils.isNotBlank(cfg.getChannel())) {
            opts.setChannel(cfg.getChannel());
        }
        if (cfg.getExecutablePath() != null) {
            opts.setExecutablePath(cfg.getExecutablePath());
        }
        if (!cfg.getArgs().isEmpty()) {
            opts.setArgs(cfg.getArgs());
        }
        if (cfg.getSlowMo() != null) {
            opts.setSlowMo(cfg.getSlowMo().toMillis());
        }
        if (cfg.getProxySettings() != null
                && cfg.getProxySettings().getHost() != null) {
            var ps = cfg.getProxySettings();
            var host = ps.getHost();
            var scheme = StringUtils.defaultIfBlank(ps.getScheme(), "http");
            var serverUrl =
                    scheme + "://" + host.getName() + ":" + host.getPort();
            var proxy = new Proxy(serverUrl);
            if (ps.getCredentials() != null
                    && StringUtils.isNotBlank(
                            ps.getCredentials().getUsername())) {
                proxy.setUsername(ps.getCredentials().getUsername());
                proxy.setPassword(
                        ps.getCredentials().getPassword());
            }
            opts.setProxy(proxy);
        }

        LOG.debug("Launching {} browser (headless={}).",
                cfg.getBrowser(), cfg.isHeadless());
        return cfg.getBrowser().browserType(pw).launch(opts);
    }

    private WebFetchResponse fetchWithBrowser(Browser browser, Doc doc) {
        var url = doc.getReference();
        var cfg = configuration;

        // Capture the primary (last navigated-to) response via listener
        var capturedResponse = new AtomicReference<Response>();

        var contextOpts = new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(cfg.isIgnoreHttpsErrors());
        if (cfg.getWindowSize() != null) {
            contextOpts.setViewportSize(
                    cfg.getWindowSize().width,
                    cfg.getWindowSize().height);
        }

        try (BrowserContext context = browser.newContext(contextOpts);
                Page page = context.newPage()) {

            // Capture the response for the navigation request
            page.onResponse(resp -> {
                // Keep the last response that matches the navigated URL
                // (Playwright fires this for every resource; we want the doc)
                if (resp.url().startsWith(url)
                        || capturedResponse.get() == null) {
                    capturedResponse.set(resp);
                }
            });

            // Set page load timeout
            if (cfg.getPageLoadTimeout() != null) {
                page.setDefaultNavigationTimeout(
                        cfg.getPageLoadTimeout().toMillis());
            }

            // Navigate
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.LOAD));

            navCountLocal.get().incrementAndGet();

            // Early script
            if (StringUtils.isNotBlank(cfg.getEarlyPageScript())) {
                page.evaluate(cfg.getEarlyPageScript());
            }

            // Wait for element
            if (StringUtils.isNotBlank(cfg.getWaitForElementSelector())) {
                var selector = toPlaywrightSelector(
                        cfg.getWaitForElementType(),
                        cfg.getWaitForElementSelector());
                var waitOpts = new Page.WaitForSelectorOptions();
                if (cfg.getWaitForElementTimeout() != null) {
                    waitOpts.setTimeout(
                            cfg.getWaitForElementTimeout().toMillis());
                }
                LOG.debug("Waiting for element '{}' on '{}'.",
                        cfg.getWaitForElementSelector(), url);
                page.waitForSelector(selector, waitOpts);
            }

            // Late script
            if (StringUtils.isNotBlank(cfg.getLatePageScript())) {
                page.evaluate(cfg.getLatePageScript());
            }

            // Get rendered page content
            var pageSource = page.content();
            doc.setInputStream(
                    IOUtils.toInputStream(pageSource, StandardCharsets.UTF_8));

            // Take screenshot if configured
            if (cfg.getScreenshotHandler() != null) {
                cfg.getScreenshotHandler().takeScreenshot(page, doc);
            }

            // Apply headers from captured response
            return buildResponse(doc, capturedResponse.get());

        } catch (Exception e) {
            LOG.error("Error fetching '{}' with Playwright: {}",
                    url, e.getMessage(), e);
            return HttpClientFetchResponse.builder()
                    .processingOutcome(ProcessingOutcome.ERROR)
                    .statusCode(-1)
                    .reasonPhrase("Playwright fetch error: " + e.getMessage())
                    .exception(e)
                    .build();
        }
    }

    private WebFetchResponse buildResponse(Doc doc, Response playwrightResp) {
        var b = HttpClientFetchResponse.builder();

        if (playwrightResp == null) {
            // No response captured (e.g. navigation to about:blank)
            if (doc.getContentType() == null) {
                doc.setContentType(ContentType.HTML);
            }
            return b.processingOutcome(ProcessingOutcome.NEW)
                    .statusCode(200)
                    .reasonPhrase("OK")
                    .build();
        }

        var statusCode = playwrightResp.status();
        var reasonPhrase = playwrightResp.statusText();

        // Apply HTTP headers
        Map<String, String> headers;
        try {
            headers = playwrightResp.headers();
        } catch (Exception e) {
            LOG.warn("Could not read response headers for '{}': {}",
                    doc.getReference(), e.getMessage());
            headers = Map.of();
        }

        for (var entry : headers.entrySet()) {
            var name = entry.getKey();
            var value = entry.getValue();
            if (HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)
                    && StringUtils.isNotBlank(value)) {
                ApacheHttpUtil.applyContentTypeAndCharset(value, doc);
            }
            doc.getMetadata().add(name, value);
        }

        // Store Norconex standard HTTP metadata
        doc.getMetadata().add(WebDocMetadata.HTTP_STATUS_CODE, statusCode);
        doc.getMetadata().add(
                WebDocMetadata.HTTP_STATUS_REASON, reasonPhrase);

        if (doc.getContentType() == null) {
            doc.setContentType(ContentType.HTML);
        }

        b.statusCode(statusCode).reasonPhrase(reasonPhrase);
        if (statusCode >= 200 && statusCode < 300) {
            b.processingOutcome(ProcessingOutcome.NEW);
        } else {
            b.processingOutcome(ProcessingOutcome.BAD_STATUS);
        }
        return b.build();
    }

    /**
     * Converts a {@link WaitElementType} + selector into a Playwright
     * CSS/XPath selector string.
     */
    static String toPlaywrightSelector(
            WaitElementType type, String selector) {
        if (type == null || type == WaitElementType.CSSSELECTOR) {
            return selector;
        }
        return switch (type) {
            case TAGNAME -> selector; // tag names are valid CSS selectors
            case CLASSNAME -> "." + selector;
            case ID -> "#" + selector;
            case XPATH -> "xpath=" + selector;
            case LINKTEXT -> "a:text-is(\"" + selector + "\")";
            case PARTIALLINKTEXT -> "a:has-text(\"" + selector + "\")";
            case NAME -> "[name='" + selector + "']";
            default -> selector;
        };
    }
}
