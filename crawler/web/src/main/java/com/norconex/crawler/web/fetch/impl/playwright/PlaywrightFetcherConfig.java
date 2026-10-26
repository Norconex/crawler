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

import java.awt.Dimension;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.commons.lang.net.ProxySettings;
import com.norconex.commons.lang.security.Credentials;
import com.norconex.crawler.core.fetch.BaseFetcherConfig;

import lombok.Data;
import lombok.NonNull;
import lombok.experimental.Accessors;

/**
 * <p>
 * Configuration for {@link PlaywrightFetcher}.
 * </p>
 *
 * <p>
 * By default Playwright uses its own downloaded browser binaries
 * ({@code ~/.cache/ms-playwright}). To use a system-installed browser instead
 * (e.g., Chrome, Edge, Firefox already present on the machine or CI runner),
 * set the {@link #getChannel() channel} field (e.g., {@code "chrome"},
 * {@code "msedge"}, {@code "firefox"}) — no download is then required.
 * No external WebDriver binary is ever needed.
 * </p>
 *
 * @see PlaywrightFetcher
 * @since 4.0.0
 */
@Data
@Accessors(chain = true)
public class PlaywrightFetcherConfig extends BaseFetcherConfig {

    /**
     * Element locator strategy for {@link #getWaitForElementSelector()}.
     */
    public enum WaitElementType {
        /** Locate by HTML tag name. */
        TAGNAME,
        /** Locate by CSS class name. */
        CLASSNAME,
        /** Locate by CSS selector expression. */
        CSSSELECTOR,
        /** Locate by element {@code id} attribute. */
        ID,
        /** Locate by visible link text. */
        LINKTEXT,
        /** Locate by {@code name} attribute. */
        NAME,
        /** Locate by partial visible link text. */
        PARTIALLINKTEXT,
        /** Locate by XPath expression. */
        XPATH
    }

    /**
     * The browser engine used for crawling.
     * Default is {@link PlaywrightBrowser#CHROMIUM}.
     */
    private PlaywrightBrowser browser = PlaywrightBrowser.CHROMIUM;

    /**
     * Whether to run the browser in headless mode.
     * Default is {@code true}.
     */
    private boolean headless = true;

    /**
     * Optional browser channel that instructs Playwright to use a
     * system-installed browser instead of its own downloaded binaries.
     * Supported values: {@code "chrome"}, {@code "msedge"},
     * {@code "firefox"}. Leave blank (the default) to use Playwright's
     * bundled browser binaries.
     */
    private String channel;

    /**
     * Optional path to a custom browser executable. When {@code null},
     * Playwright uses the bundled browser binary it downloaded.
     */
    private Path executablePath;

    /**
     * Optional command-line arguments passed to the browser at launch.
     */
    private final List<String> args = new ArrayList<>();

    /**
     * When {@code true}, HTTPS certificate errors are ignored.
     * Default is {@code false}.
     */
    private boolean ignoreHttpsErrors;

    /**
     * The user agent the browser sends, in place of its own. It is set on the
     * browser context, so both the requests and the page's own
     * {@code navigator.userAgent} carry it. Default is {@code null} (the
     * browser's own).
     */
    private String userAgent;

    /**
     * The preferred color scheme pages are told about (the
     * {@code prefers-color-scheme} media query). Default is {@code null}
     * (the browser's own).
     */
    public enum ColorScheme {
        LIGHT, DARK, NO_PREFERENCE;
    }

    /**
     * The locale the browser reports (e.g., {@code fr-CA}). It sets the
     * {@code Accept-Language} header and the page's {@code navigator.language}.
     * Default is {@code null} (the browser's own).
     */
    private String locale;

    /**
     * The timezone the browser reports (an IANA name, e.g.,
     * {@code America/Toronto}). Default is {@code null} (the system's).
     */
    private String timezoneId;

    /**
     * Whether the browser runs the page's JavaScript. Turn off to read pages
     * as they are served, without any script changing them. Default is
     * {@code true}.
     */
    private boolean javaScriptEnabled = true;

    /**
     * Whether to ignore the page's Content-Security-Policy, so scripts added
     * by {@link #getEarlyPageScript()} or {@link #getLatePageScript()} are
     * not blocked by it. Default is {@code false}.
     */
    private boolean bypassCsp;

    /**
     * Whether to keep service workers from registering. A service worker can
     * answer requests itself, so what is read may not be what the site
     * serves. Default is {@code false}.
     */
    private boolean blockServiceWorkers;

    /**
     * Whether to behave as a mobile device: the page is told it is mobile
     * and the screen is touch-enabled. Combine with a mobile user agent and a
     * small window size. Default is {@code false}.
     */
    private boolean mobile;

    /**
     * The preferred color scheme. Default is {@code null}.
     */
    private ColorScheme colorScheme;

    /**
     * The credentials the browser answers HTTP Basic or Digest
     * authentication challenges with. Not sent unless the site asks.
     */
    private final Credentials httpCredentials = new Credentials();

    /**
     * Extra HTTP headers sent with every request.
     */
    private final Map<String, String> extraHttpHeaders = new HashMap<>();

    /**
     * A file Playwright wrote of a signed-in session (cookies and local
     * storage, as saved by {@code context.storageState()}). The browser starts
     * with that session. Default is {@code null}.
     */
    private Path storageStatePath;

    /**
     * Optional slow-motion delay applied between Playwright operations.
     * Useful for debugging. Default is {@code null} (no delay).
     */
    private Duration slowMo;

    /**
     * Maximum time to wait for the page load event. When {@code null},
     * the Playwright default (30 s) applies.
     */
    private Duration pageLoadTimeout;

    /**
     * Optional JavaScript code executed immediately after page navigation,
     * before any wait conditions are applied.
     */
    private String earlyPageScript;

    /**
     * Optional JavaScript code executed after all wait conditions have been
     * satisfied.
     */
    private String latePageScript;

    /**
     * The type of reference used when waiting for an element to appear.
     * Used together with {@link #getWaitForElementSelector()}.
     * Default is {@link WaitElementType#CSSSELECTOR}.
     */
    private WaitElementType waitForElementType = WaitElementType.CSSSELECTOR;

    /**
     * Selector expression used to wait for a specific element to appear
     * in the page before returning. The kind of expression is defined by
     * {@link #getWaitForElementType()}. When {@code null}, no element wait
     * is performed.
     */
    private String waitForElementSelector;

    /**
     * Maximum time to wait for the element identified by
     * {@link #getWaitForElementSelector()} to appear.
     * When {@code null}, the Playwright default timeout applies.
     */
    private Duration waitForElementTimeout;

    /**
     * Optional browser viewport size. When {@code null}, the Playwright
     * default viewport is used.
     */
    private Dimension windowSize;

    /**
     * Proxy settings used when launching the browser. When not set (default),
     * no proxy is configured.
     */
    private ProxySettings proxySettings;

    /**
     * A maximum number of navigations after which each per-thread browser
     * instance is restarted. Browsers can accumulate memory over time;
     * restarting periodically can improve stability.
     * Default is {@code -1} (unlimited – no restart).
     */
    private int browserMaxNavigations = -1;

    /**
     * Maximum age of a per-thread browser instance before it is restarted.
     * Browsers can accumulate memory over time; restarting periodically
     * can improve stability.
     * Default is {@code null} (no age-based restart).
     */
    private Duration browserMaxAge;

    /**
     * Interval between checks for idle (unused) browser instances to close.
     * Cannot be {@code null}. Defaults to 10 seconds.
     */
    @NonNull
    private Duration cleanupInterval = Duration.ofSeconds(10);

    /**
     * When configured, takes a screenshot of each fetched page and stores it
     * according to the handler's configuration (document metadata field or
     * local directory).
     */
    private PlaywrightScreenshotHandler screenshotHandler;

    /**
     * Gets the credentials for HTTP authentication challenges.
     * @return credentials
     */
    public Credentials getHttpCredentials() {
        return httpCredentials;
    }

    /**
     * Sets the credentials for HTTP authentication challenges.
     * @param credentials the credentials
     * @return this config
     */
    public PlaywrightFetcherConfig setHttpCredentials(Credentials credentials) {
        httpCredentials.copyFrom(credentials);
        return this;
    }

    /**
     * Returns an unmodifiable view of the extra HTTP headers.
     * @return headers by name
     */
    public Map<String, String> getExtraHttpHeaders() {
        return Collections.unmodifiableMap(extraHttpHeaders);
    }

    /**
     * Sets the extra HTTP headers sent with every request.
     * @param headers header names and values
     * @return this config
     */
    public PlaywrightFetcherConfig
            setExtraHttpHeaders(Map<String, String> headers) {
        CollectionUtil.setAll(extraHttpHeaders, headers);
        return this;
    }

    /**
     * Returns an unmodifiable view of the browser launch arguments.
     * Use {@link #setArgs(List)} to replace the list.
     * @return unmodifiable list of launch arguments
     */
    public List<String> getArgs() {
        return Collections.unmodifiableList(args);
    }

    /**
     * Sets the browser launch arguments.
     * @param args launch arguments
     * @return this config
     */
    public PlaywrightFetcherConfig setArgs(List<String> args) {
        CollectionUtil.setAll(this.args, args);
        return this;
    }
}
