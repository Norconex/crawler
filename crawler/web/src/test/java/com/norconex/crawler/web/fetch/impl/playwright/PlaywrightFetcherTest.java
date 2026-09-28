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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.BrowserType.LaunchOptions;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.norconex.commons.lang.net.Host;
import com.norconex.commons.lang.net.ProxySettings;
import com.norconex.commons.lang.security.Credentials;
import com.norconex.crawler.core.fetch.FetchException;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.web.fetch.HttpMethod;
import com.norconex.crawler.web.fetch.WebFetchRequest;
import com.norconex.crawler.web.fetch.impl.playwright.PlaywrightFetcherConfig.WaitElementType;
import com.norconex.crawler.web.stubs.CrawlDocStubs;

@Timeout(30)
class PlaywrightFetcherTest {

    // -------------------------------------------------------------------------
    // acceptRequest / unsupported HTTP method guards
    // -------------------------------------------------------------------------

    @Test
    void testUnsupportedHttpMethodHead() throws FetchException {
        var response = new PlaywrightFetcher().fetch(
                new WebFetchRequest(
                        CrawlDocStubs.crawlDocHtml(
                                "http://example.com"),
                        HttpMethod.HEAD));
        assertThat(response.getReasonPhrase())
                .contains("To obtain headers");
        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.UNSUPPORTED);
        assertThat(response.getStatusCode()).isEqualTo(-1);
    }

    @Test
    void testUnsupportedHttpMethodPost() throws FetchException {
        var response = new PlaywrightFetcher().fetch(
                new WebFetchRequest(
                        CrawlDocStubs.crawlDocHtml(
                                "http://example.com"),
                        HttpMethod.POST));
        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.UNSUPPORTED);
    }

    @Test
    void testAcceptRequestOnlyGet() {
        var fetcher = new PlaywrightFetcher();
        var docHtml = CrawlDocStubs.crawlDocHtml("http://example.com");
        assertThat(fetcher.acceptRequest(
                new WebFetchRequest(docHtml, HttpMethod.GET)))
                        .isTrue();
        assertThat(fetcher.acceptRequest(
                new WebFetchRequest(docHtml, HttpMethod.HEAD)))
                        .isFalse();
        assertThat(fetcher.acceptRequest(
                new WebFetchRequest(docHtml, HttpMethod.POST)))
                        .isFalse();
    }

    // -------------------------------------------------------------------------
    // toPlaywrightSelector utility
    // -------------------------------------------------------------------------

    @Test
    void testToPlaywrightSelectorNullType() {
        assertThat(PlaywrightFetcher.toPlaywrightSelector(null,
                "selector"))
                        .isEqualTo("selector");
    }

    @Test
    void testToPlaywrightSelectorCssSelector() {
        assertThat(PlaywrightFetcher.toPlaywrightSelector(
                WaitElementType.CSSSELECTOR, ".my-class"))
                        .isEqualTo(".my-class");
    }

    @Test
    void testToPlaywrightSelectorTagName() {
        assertThat(PlaywrightFetcher.toPlaywrightSelector(
                WaitElementType.TAGNAME, "div"))
                        .isEqualTo("div");
    }

    @Test
    void testToPlaywrightSelectorClassName() {
        assertThat(PlaywrightFetcher.toPlaywrightSelector(
                WaitElementType.CLASSNAME, "my-class"))
                        .isEqualTo(".my-class");
    }

    @Test
    void testToPlaywrightSelectorId() {
        assertThat(PlaywrightFetcher.toPlaywrightSelector(
                WaitElementType.ID, "header"))
                        .isEqualTo("#header");
    }

    @Test
    void testToPlaywrightSelectorXpath() {
        assertThat(PlaywrightFetcher.toPlaywrightSelector(
                WaitElementType.XPATH, "//div[@id='main']"))
                        .isEqualTo("xpath=//div[@id='main']");
    }

    @Test
    void testToPlaywrightSelectorLinkText() {
        assertThat(PlaywrightFetcher.toPlaywrightSelector(
                WaitElementType.LINKTEXT, "Click here"))
                        .isEqualTo("a:text-is(\"Click here\")");
    }

    @Test
    void testToPlaywrightSelectorPartialLinkText() {
        assertThat(PlaywrightFetcher.toPlaywrightSelector(
                WaitElementType.PARTIALLINKTEXT, "Click"))
                        .isEqualTo("a:has-text(\"Click\")");
    }

    @Test
    void testToPlaywrightSelectorName() {
        assertThat(PlaywrightFetcher.toPlaywrightSelector(
                WaitElementType.NAME, "username"))
                        .isEqualTo("[name='username']");
    }

    // -------------------------------------------------------------------------
    // Browser fetch with mocked Playwright
    // -------------------------------------------------------------------------

    @Test
    void testFetchSuccess() throws FetchException {
        var doc = CrawlDocStubs.crawlDocHtml("http://example.com");
        var fetcher = createFetcherWithMockedBrowser(
                200, "OK", Map.of("content-type", "text/html"),
                "<html/>");

        var response = fetcher.fetch(
                new WebFetchRequest(doc, HttpMethod.GET));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(response.getStatusCode()).isEqualTo(200);
    }

    @Test
    void testFetchBadStatus() throws FetchException {
        var doc = CrawlDocStubs.crawlDocHtml("http://example.com");
        var fetcher = createFetcherWithMockedBrowser(
                404, "Not Found", Map.of(),
                "<html>Not found</html>");

        var response = fetcher.fetch(
                new WebFetchRequest(doc, HttpMethod.GET));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.BAD_STATUS);
        assertThat(response.getStatusCode()).isEqualTo(404);
        assertThat(response.getReasonPhrase()).isEqualTo("Not Found");
    }

    @Test
    void testFetchNullResponseHandled() throws FetchException {
        var doc = CrawlDocStubs.crawlDocHtml("http://example.com");
        var fetcher = createFetcherWithMockedBrowserNoResponse(
                "<html/>");

        var response = fetcher.fetch(
                new WebFetchRequest(doc, HttpMethod.GET));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(response.getStatusCode()).isEqualTo(200);
    }

    @Test
    void testFetchWithEarlyAndLatePageScript() throws FetchException {
        var doc = CrawlDocStubs.crawlDocHtml("http://example.com");
        var page = mock(Page.class);
        when(page.content()).thenReturn("<html/>");
        // Response listener is registered via onResponse — simulate null resp
        var fetcher = createFetcherWithPage(page, null);
        fetcher.getConfiguration()
                .setEarlyPageScript("console.log('early');")
                .setLatePageScript("console.log('late');");

        var response = fetcher.fetch(
                new WebFetchRequest(doc, HttpMethod.GET));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        verify(page).evaluate("console.log('early');");
        verify(page).evaluate("console.log('late');");
    }

    @Test
    void testFetchWithWaitForElement() throws FetchException {
        var doc = CrawlDocStubs.crawlDocHtml("http://example.com");
        var page = mock(Page.class);
        when(page.content()).thenReturn("<html/>");
        var fetcher = createFetcherWithPage(page, null);
        fetcher.getConfiguration()
                .setWaitForElementType(WaitElementType.ID)
                .setWaitForElementSelector("main")
                .setWaitForElementTimeout(
                        Duration.ofSeconds(5));

        var response = fetcher.fetch(
                new WebFetchRequest(doc, HttpMethod.GET));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        verify(page).waitForSelector(anyString(), any());
    }

    @Test
    void testFetchWithPageLoadTimeout() throws FetchException {
        var doc = CrawlDocStubs.crawlDocHtml("http://example.com");
        var page = mock(Page.class);
        when(page.content()).thenReturn("<html/>");
        var fetcher = createFetcherWithPage(page, null);
        fetcher.getConfiguration()
                .setPageLoadTimeout(Duration.ofSeconds(10));

        var response = fetcher.fetch(
                new WebFetchRequest(doc, HttpMethod.GET));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        verify(page).setDefaultNavigationTimeout(10_000);
    }

    @Test
    void testFetchWithWindowSize() throws FetchException {
        var doc = CrawlDocStubs.crawlDocHtml("http://example.com");
        var page = mock(Page.class);
        when(page.content()).thenReturn("<html/>");
        var fetcher = createFetcherWithPage(page, null);
        fetcher.getConfiguration()
                .setWindowSize(new java.awt.Dimension(1024,
                        768));

        var response = fetcher.fetch(
                new WebFetchRequest(doc, HttpMethod.GET));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
    }

    @Test
    void testFetchErrorReturnsErrorOutcome() throws FetchException {
        var doc = CrawlDocStubs.crawlDocHtml("http://example.com");
        // page.content() throws to simulate a navigation failure
        var page = mock(Page.class);
        when(page.content())
                .thenThrow(new RuntimeException("nav error"));
        var fetcher = createFetcherWithPage(page, null);

        var response = fetcher.fetch(
                new WebFetchRequest(doc, HttpMethod.GET));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.ERROR);
        assertThat(response.getReasonPhrase()).contains("nav error");
    }

    @Test
    void testFetchWithContentTypeHeader() throws FetchException {
        var doc = CrawlDocStubs.crawlDocHtml("http://example.com");
        var fetcher = createFetcherWithMockedBrowser(
                200, "OK",
                Map.of("content-type",
                        "application/json; charset=UTF-8"),
                "{\"key\":\"value\"}");

        var response = fetcher.fetch(
                new WebFetchRequest(doc, HttpMethod.GET));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(doc.getMetadata().getString("content-type"))
                .containsIgnoringCase("application/json");
    }

    @Test
    void testFetchWithProxySettings() throws FetchException {
        var doc = CrawlDocStubs.crawlDocHtml("http://example.com");
        var page = mock(Page.class);
        when(page.content()).thenReturn("<html/>");

        var fetcher = createFetcherWithPage(page, null);
        fetcher.getConfiguration().setProxySettings(
                new ProxySettings(new Host("proxy.example.com",
                        3128)));

        var response = fetcher.fetch(
                new WebFetchRequest(doc, HttpMethod.GET));

        // Response should still succeed
        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
    }

    @Test
    void testFetchWithUnreadableResponseHeadersStillSucceeds()
            throws FetchException {
        var doc = CrawlDocStubs.crawlDocHtml("http://example.com");
        var response = mock(Response.class);
        when(response.status()).thenReturn(200);
        when(response.statusText()).thenReturn("OK");
        when(response.url()).thenReturn("http://example.com");
        when(response.headers())
                .thenThrow(new RuntimeException("no headers"));

        var page = mock(Page.class);
        when(page.content()).thenReturn("<html/>");
        var fetcher = createFetcherWithPage(page, response);

        var fetchResponse = fetcher.fetch(
                new WebFetchRequest(doc, HttpMethod.GET));

        assertThat(fetchResponse.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(doc.getContentType()).isNotNull();
    }

    @Test
    void testFetcherShutdownClosesAllPlaywrights() {
        var fetcher = new PlaywrightFetcher();
        var pw1 = mock(Playwright.class);
        var pw2 = mock(Playwright.class);
        fetcher.allPlaywrights.add(pw1);
        fetcher.allPlaywrights.add(pw2);

        fetcher.fetcherShutdown(null);

        verify(pw1).close();
        verify(pw2).close();
        assertThat(fetcher.allPlaywrights).isEmpty();
    }

    @Test
    void testFetcherShutdownHandlesCloseException() {
        var fetcher = new PlaywrightFetcher();
        var pw = mock(Playwright.class);
        doThrow(new RuntimeException("close error")).when(pw).close();
        fetcher.allPlaywrights.add(pw);

        // Should not throw
        fetcher.fetcherShutdown(null);
        assertThat(fetcher.allPlaywrights).isEmpty();
    }

    @Test
    void testFetcherStartupIsNoOp() {
        // fetcherStartup only logs; verify no exception is thrown
        var fetcher = new PlaywrightFetcher();
        fetcher.fetcherStartup(null);
        // No browser should be created at startup
        assertThat(fetcher.browserLocal.get()).isNull();
    }

    @Test
    void testBrowserRecycleOnMaxNavigations() throws FetchException {
        var doc = CrawlDocStubs.crawlDocHtml("http://example.com");
        var page = mock(Page.class);
        when(page.content()).thenReturn("<html/>");

        // Uses a fetcher that runs the real getOrCreateBrowser() so the
        // recycle + navCount reset logic is actually exercised.
        var fetcher = createFetcherForRecycleTest(page);
        fetcher.getConfiguration().setBrowserMaxNavigations(1);

        // First fetch — browser is created; nav count becomes 1
        fetcher.fetch(new WebFetchRequest(doc, HttpMethod.GET));
        // Second fetch — maxNavigations reached; browser recycled, count reset
        fetcher.fetch(new WebFetchRequest(doc, HttpMethod.GET));

        // After recycle the counter was reset to 0 then incremented once → 1
        assertThat(fetcher.navCountLocal.get().get()).isEqualTo(1);
    }

    @Test
    void testBrowserRecycleOnMaxAge() throws FetchException {
        var doc = CrawlDocStubs.crawlDocHtml("http://example.com");
        var page = mock(Page.class);
        when(page.content()).thenReturn("<html/>");

        // Uses a fetcher that runs the real getOrCreateBrowser() so the
        // recycle + navCount reset logic is actually exercised.
        var fetcher = createFetcherForRecycleTest(page);
        fetcher.getConfiguration()
                .setBrowserMaxAge(Duration.ofMinutes(5));

        // First fetch — browser is created; nav count becomes 1
        fetcher.fetch(new WebFetchRequest(doc, HttpMethod.GET));

        // Age the browser past its limit explicitly. Setting a tiny max age
        // and fetching twice instead would be a race: both fetches hit mocks
        // microseconds apart, and Instant.now() need not advance between
        // them, in which case nothing is ever older than its max age.
        fetcher.browserStartTimeLocal.set(
                Instant.now().minus(Duration.ofMinutes(10)));

        // Second fetch — browser is stale; recycled and count reset
        fetcher.fetch(new WebFetchRequest(doc, HttpMethod.GET));

        // After recycle the counter was reset to 0 then incremented once → 1
        assertThat(fetcher.navCountLocal.get().get()).isEqualTo(1);
    }

    @Test
    void testGetOrCreateBrowserAppliesLaunchConfiguration() {
        var browser = mock(Browser.class);
        var browserType = mock(BrowserType.class);
        when(browserType.launch(any())).thenReturn(browser);

        var pw = mock(Playwright.class);
        when(pw.chromium()).thenReturn(browserType);

        var proxySettings = new ProxySettings(
                new Host("proxy.example.com", 3128));
        proxySettings.setScheme("socks5");
        proxySettings.setCredentials(new Credentials("user", "pass"));

        var fetcher = new PlaywrightFetcher();
        fetcher.playwrightLocal.set(pw);
        fetcher.getConfiguration()
                .setHeadless(false)
                .setChannel("chrome")
                .setExecutablePath(Path.of("custom-browser"))
                .setArgs(List.of("--flag"))
                .setSlowMo(Duration.ofMillis(250))
                .setProxySettings(proxySettings);

        var createdBrowser = fetcher.getOrCreateBrowser();

        assertThat(createdBrowser).isSameAs(browser);
        verify(browserType).launch(any(LaunchOptions.class));
    }

    @Test
    void testGetOrCreateBrowserRecyclesWhenCloseFails() {
        var existing = mock(Browser.class);
        when(existing.isConnected()).thenReturn(true);
        doThrow(new RuntimeException("close failed")).when(existing)
                .close();

        var replacement = mock(Browser.class);
        var browserType = mock(BrowserType.class);
        when(browserType.launch(any())).thenReturn(replacement);

        var pw = mock(Playwright.class);
        when(pw.chromium()).thenReturn(browserType);

        var fetcher = new PlaywrightFetcher();
        fetcher.playwrightLocal.set(pw);
        fetcher.browserLocal.set(existing);
        fetcher.navCountLocal.get().set(1);
        fetcher.getConfiguration().setBrowserMaxNavigations(1);

        var browser = fetcher.getOrCreateBrowser();

        assertThat(browser).isSameAs(replacement);
        verify(existing).close();
    }

    @Test
    void testCloseAllPlaywrightInstancesClearsTrackedSet()
            throws Exception {
        var field = PlaywrightFetcher.class.getDeclaredField(
                "ALL_PLAYWRIGHT_INSTANCES");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<Playwright> instances = (Set<Playwright>) field.get(null);
        var pw1 = mock(Playwright.class);
        var pw2 = mock(Playwright.class);
        doThrow(new RuntimeException("close error")).when(pw2).close();
        instances.add(pw1);
        instances.add(pw2);

        var method = PlaywrightFetcher.class.getDeclaredMethod(
                "closeAllPlaywrightInstances");
        method.setAccessible(true);
        method.invoke(null);

        verify(pw1).close();
        verify(pw2).close();
        assertThat(instances).isEmpty();
    }

    @Test
    void testNoWaitForElementWhenSelectorIsBlank() throws FetchException {
        var doc = CrawlDocStubs.crawlDocHtml("http://example.com");
        var page = mock(Page.class);
        when(page.content()).thenReturn("<html/>");
        var fetcher = createFetcherWithPage(page, null);
        fetcher.getConfiguration()
                .setWaitForElementType(WaitElementType.ID)
                .setWaitForElementSelector(""); // blank — should NOT wait

        fetcher.fetch(new WebFetchRequest(doc, HttpMethod.GET));

        verify(page, never()).waitForSelector(anyString(), any());
    }

    // -------------------------------------------------------------------------
    // PlaywrightBrowser enum coverage
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(PlaywrightBrowser.class)
    void testPlaywrightBrowserDelegatesToCorrectBrowserType(
            PlaywrightBrowser browser) {
        var pw = mock(Playwright.class);
        var bt = mock(BrowserType.class);
        when(pw.chromium()).thenReturn(bt);
        when(pw.firefox()).thenReturn(bt);
        when(pw.webkit()).thenReturn(bt);

        var result = browser.browserType(pw);
        assertThat(result).isSameAs(bt);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a {@link PlaywrightFetcher} that uses the given pre-built
     * {@link Page} mock and an optional mocked {@link Response}, bypassing
     * real Playwright/Browser creation.
     */
    private PlaywrightFetcher createFetcherWithPage(
            Page page, Response playwrightResp) {
        var context = mock(BrowserContext.class);
        when(context.newPage()).thenReturn(page);

        var browser = mock(Browser.class);
        when(browser.isConnected()).thenReturn(true);
        when(browser.newContext(any())).thenReturn(context);

        // Register the response listener via onResponse and immediately fire it
        if (playwrightResp != null) {
            // onResponse returns void — must use doAnswer, not when().thenAnswer()
            doAnswer(inv -> {
                Consumer<Response> consumer =
                        inv.getArgument(0);
                consumer.accept(playwrightResp);
                return null;
            }).when(page).onResponse(any());
        }

        var fetcher = new PlaywrightFetcher() {
            @Override
            Browser getOrCreateBrowser() {
                return browser;
            }
        };
        return fetcher;
    }

    private PlaywrightFetcher createFetcherWithMockedBrowser(
            int statusCode,
            String reasonPhrase,
            Map<String, String> headers,
            String pageContent) {
        var resp = mock(Response.class);
        when(resp.status()).thenReturn(statusCode);
        when(resp.statusText()).thenReturn(reasonPhrase);
        when(resp.headers()).thenReturn(headers);
        when(resp.url()).thenReturn("http://example.com");

        var page = mock(Page.class);
        when(page.content()).thenReturn(pageContent);

        return createFetcherWithPage(page, resp);
    }

    /**
     * Creates a {@link PlaywrightFetcher} whose {@code getOrCreateBrowser()}
     * is NOT overridden, so the real recycle/reset logic executes. The
     * Playwright stack (Playwright → BrowserType → Browser → Context → Page)
     * is fully mocked to avoid real browser processes.
     */
    private PlaywrightFetcher createFetcherForRecycleTest(Page page) {
        var context = mock(BrowserContext.class);
        when(context.newPage()).thenReturn(page);

        var browser = mock(Browser.class);
        when(browser.isConnected()).thenReturn(true);
        when(browser.newContext(any())).thenReturn(context);

        var browserType = mock(BrowserType.class);
        when(browserType.launch(any())).thenReturn(browser);

        var pw = mock(Playwright.class);
        when(pw.chromium()).thenReturn(browserType);
        when(pw.firefox()).thenReturn(browserType);
        when(pw.webkit()).thenReturn(browserType);

        var fetcher = new PlaywrightFetcher();
        // Pre-seed the per-thread Playwright so Playwright.create() is skipped
        fetcher.playwrightLocal.set(pw);
        return fetcher;
    }

    private PlaywrightFetcher createFetcherWithMockedBrowserNoResponse(
            String pageContent) {
        var page = mock(Page.class);
        when(page.content()).thenReturn(pageContent);
        return createFetcherWithPage(page, null);
    }
}
