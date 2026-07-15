/* Copyright 2015-2026 Norconex Inc.
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
package com.norconex.crawler.web.fetch.impl.httpclient;

import static com.norconex.crawler.web.fetch.impl.httpclient.HttpClientFetcher.SCHEME_PORT_RESOLVER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.bean.BeanUtil;
import com.norconex.commons.lang.net.Host;
import com.norconex.commons.lang.security.Credentials;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.web.WebTestUtil;
import com.norconex.crawler.web.fetch.HttpMethod;
import com.norconex.crawler.web.fetch.WebFetchRequest;
import com.norconex.crawler.web.fetch.WebFetchResponse;
import com.norconex.crawler.web.stubs.CrawlDocStubs;

@Timeout(30)
class HttpClientFetcherTest {

    @Test
    void testWriteRead() {
        var cfg = WebTestUtil.randomize(HttpClientFetcherConfig.class);
        var f = new HttpClientFetcher();
        BeanUtil.copyProperties(f.getConfiguration(), cfg);
        assertThatNoException()
                .isThrownBy(() -> BeanMapper.DEFAULT.assertWriteRead(f));
    }

    @Test
    void testShemePortResolver() throws URISyntaxException {
        assertThat(SCHEME_PORT_RESOLVER.resolve(
                HttpHost.create("http://blah.com"))).isEqualTo(80);
        assertThat(SCHEME_PORT_RESOLVER.resolve(
                HttpHost.create("https://blah.com"))).isEqualTo(443);
        assertThat(SCHEME_PORT_RESOLVER.resolve(
                HttpHost.create("ftp://blah.com"))).isEqualTo(80);
    }

    // -------------------------------------------------------------------------
    // acceptRequest
    // -------------------------------------------------------------------------

    @Test
    void testAcceptRequestGetAndHead() {
        var fetcher = new HttpClientFetcher();
        var doc = CrawlDocStubs.crawlDocHtml("http://example.com");
        assertThat(fetcher.acceptRequest(
                new WebFetchRequest(doc, HttpMethod.GET))).isTrue();
        assertThat(fetcher.acceptRequest(
                new WebFetchRequest(doc, HttpMethod.HEAD))).isTrue();
    }

    @Test
    void testAcceptRequestPostNotInDefaultList() {
        var fetcher = new HttpClientFetcher();
        var doc = CrawlDocStubs.crawlDocHtml("http://example.com");
        assertThat(fetcher.acceptRequest(
                new WebFetchRequest(doc, HttpMethod.POST))).isFalse();
    }

    @Test
    void testAcceptRequestCustomMethods() {
        var fetcher = new HttpClientFetcher();
        fetcher.getConfiguration().setHttpMethods(
                List.of(HttpMethod.GET, HttpMethod.POST));
        var doc = CrawlDocStubs.crawlDocHtml("http://example.com");
        assertThat(fetcher.acceptRequest(
                new WebFetchRequest(doc, HttpMethod.POST))).isTrue();
        assertThat(fetcher.acceptRequest(
                new WebFetchRequest(doc, HttpMethod.HEAD))).isFalse();
    }

    // -------------------------------------------------------------------------
    // getUserAgent
    // -------------------------------------------------------------------------

    @Test
    void testGetUserAgent() {
        var fetcher = new HttpClientFetcher();
        fetcher.getConfiguration().setUserAgent("MyBot/1.0");
        assertThat(fetcher.getUserAgent()).isEqualTo("MyBot/1.0");
    }

    // -------------------------------------------------------------------------
    // fetcherStartup / fetcherShutdown
    // -------------------------------------------------------------------------

    @Test
    void testFetcherStartupCreatesHttpClient() {
        var fetcher = new HttpClientFetcher();
        fetcher.fetcherStartup(null);
        assertThat(fetcher.getHttpClient()).isNotNull();
    }

    @Test
    void testFetcherShutdownDoesNotThrow() {
        var fetcher = new HttpClientFetcher();
        fetcher.fetcherStartup(null);
        assertThatNoException().isThrownBy(() -> fetcher.fetcherShutdown(null));
    }

    @Test
    void testFetcherStartupWithUserAgent() {
        var fetcher = new HttpClientFetcher();
        fetcher.getConfiguration().setUserAgent("CoverageBot/1.0");
        fetcher.fetcherStartup(null);
        assertThat(fetcher.getHttpClient()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // createProxy
    // -------------------------------------------------------------------------

    @Test
    void testCreateProxyNotSet() {
        var fetcher = new HttpClientFetcher();
        assertThat(fetcher.createProxy()).isNull();
    }

    @Test
    void testCreateProxySet() {
        var fetcher = new HttpClientFetcher();
        fetcher.getConfiguration().getProxySettings()
                .setHost(new Host("proxy.example.com", 3128));
        assertThat(fetcher.createProxy()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // createRoutePlanner
    // -------------------------------------------------------------------------

    @Test
    void testCreateRoutePlannerNoLocalAddress() {
        var fetcher = new HttpClientFetcher();
        assertThat(fetcher.createRoutePlanner(
                fetcher.createSchemePortResolver())).isNull();
    }

    @Test
    void testCreateRoutePlannerWithLocalAddress() {
        var fetcher = new HttpClientFetcher();
        fetcher.getConfiguration().setLocalAddress("127.0.0.1");
        assertThat(fetcher.createRoutePlanner(
                fetcher.createSchemePortResolver())).isNotNull();
    }

    // -------------------------------------------------------------------------
    // createSSLContext
    // -------------------------------------------------------------------------

    @Test
    void testCreateSSLContextNotTrustAll() {
        var fetcher = new HttpClientFetcher();
        assertThat(fetcher.createSSLContext()).isNull();
    }

    @Test
    void testCreateSSLContextTrustAll() {
        var fetcher = new HttpClientFetcher();
        fetcher.getConfiguration().setTrustAllSSLCertificates(true);
        assertThat(fetcher.createSSLContext()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // createTlsSocketStrategy
    // -------------------------------------------------------------------------

    @Test
    void testCreateTlsSocketStrategyNoConfig() {
        var fetcher = new HttpClientFetcher();
        assertThat(fetcher.createTlsSocketStrategy(null)).isNull();
    }

    @Test
    void testCreateTlsSocketStrategyTrustAll() {
        var fetcher = new HttpClientFetcher();
        fetcher.getConfiguration().setTrustAllSSLCertificates(true);
        assertThat(fetcher.createTlsSocketStrategy(
                fetcher.createSSLContext())).isNotNull();
    }

    @Test
    void testCreateTlsSocketStrategyWithProtocols() {
        var fetcher = new HttpClientFetcher();
        fetcher.getConfiguration().setSslProtocols(List.of("TLSv1.2"));
        assertThat(fetcher.createTlsSocketStrategy(null)).isNotNull();
    }

    @Test
    void testCreateTlsSocketStrategyWithSniDisabled() {
        var fetcher = new HttpClientFetcher();
        fetcher.getConfiguration()
                .setTrustAllSSLCertificates(true)
                .setSniDisabled(true);
        assertThat(fetcher.createTlsSocketStrategy(
                fetcher.createSSLContext())).isNotNull();
    }

    // -------------------------------------------------------------------------
    // createDefaultRequestHeaders — preemptive paths
    // -------------------------------------------------------------------------

    @Test
    void testCreateDefaultRequestHeadersPreemptiveWithBasic() {
        var fetcher = new HttpClientFetcher();
        var authCfg = new HttpAuthConfig();
        authCfg.setPreemptive(true);
        authCfg.setMethod(HttpAuthMethod.BASIC);
        authCfg.setCredentials(new Credentials("user", "pass"));
        fetcher.getConfiguration().setAuthentication(authCfg);

        var headers = fetcher.createDefaultRequestHeaders();

        assertThat(headers)
                .extracting(h -> h.getName())
                .contains("Authorization");
    }

    @Test
    void testCreateDefaultRequestHeadersPreemptiveNoUsername() {
        var fetcher = new HttpClientFetcher();
        var authCfg = new HttpAuthConfig();
        authCfg.setPreemptive(true);
        authCfg.setMethod(HttpAuthMethod.BASIC);
        // no credentials set — blank username
        fetcher.getConfiguration().setAuthentication(authCfg);

        var headers = fetcher.createDefaultRequestHeaders();

        // Should return early — no Authorization header
        assertThat(headers)
                .noneMatch(h -> "Authorization".equals(h.getName()));
    }

    @Test
    void testCreateDefaultRequestHeadersPreemptiveNonBasicMethod() {
        var fetcher = new HttpClientFetcher();
        var authCfg = new HttpAuthConfig();
        authCfg.setPreemptive(true);
        authCfg.setMethod(HttpAuthMethod.DIGEST);
        authCfg.setCredentials(new Credentials("user", "pass"));
        fetcher.getConfiguration().setAuthentication(authCfg);

        // Should log a warning but still add the Authorization header
        var headers = fetcher.createDefaultRequestHeaders();

        assertThat(headers)
                .extracting(h -> h.getName())
                .contains("Authorization");
    }

    @Test
    void testCreateDefaultRequestHeadersIncludesConfiguredHeaders() {
        var fetcher = new HttpClientFetcher();
        fetcher.getConfiguration().setRequestHeader("X-Test", "value");

        List<Header> headers = fetcher.createDefaultRequestHeaders();

        assertThat(headers)
                .extracting(Header::getName)
                .containsExactly("X-Test");
        assertThat(headers)
                .extracting(Header::getValue)
                .containsExactly("value");
    }

    // -------------------------------------------------------------------------
    // createCredentialsProvider — proxy creds
    // -------------------------------------------------------------------------

    @Test
    void testCreateCredentialsProviderProxyCreds() {
        var fetcher = new HttpClientFetcher();
        fetcher.getConfiguration().getProxySettings()
                .setHost(new Host("proxy.example.com", 3128))
                .setCredentials(new Credentials("proxyUser", "proxyPass"));

        var provider = fetcher.createCredentialsProvider();

        assertThat(provider).isNotNull();
    }

    @Test
    void testCreateCredentialsProviderAuthHostCreds() {
        var fetcher = new HttpClientFetcher();
        var authCfg = new HttpAuthConfig();
        authCfg.setMethod(HttpAuthMethod.BASIC);
        authCfg.setHost(new Host("example.com", 80));
        authCfg.setCredentials(new Credentials("user", "pass"));
        fetcher.getConfiguration().setAuthentication(authCfg);

        var provider = fetcher.createCredentialsProvider();

        assertThat(provider).isNotNull();
    }

    @Test
    void testCreateCredentialsProviderFormAuthIgnored() {
        var fetcher = new HttpClientFetcher();
        var authCfg = new HttpAuthConfig();
        authCfg.setMethod(HttpAuthMethod.FORM);
        authCfg.setHost(new Host("example.com", 80));
        authCfg.setCredentials(new Credentials("user", "pass"));
        fetcher.getConfiguration().setAuthentication(authCfg);

        var provider = fetcher.createCredentialsProvider();

        assertThat(provider).isNull();
    }

    @Test
    void testCreateRequestConfigWithConfiguredValues() {
        var fetcher = new HttpClientFetcher();
        fetcher.getConfiguration()
                .setConnectionRequestTimeout(Duration.ofSeconds(2))
                .setMaxRedirects(0)
                .setExpectContinueEnabled(true)
                .setCookieSpec(HttpClientFetcherConfig.CookieSpec.STRICT);

        RequestConfig config = fetcher.createRequestConfig();

        assertThat(config).isNotNull();
        assertThat(config.isRedirectsEnabled()).isFalse();
        assertThat(config.isExpectContinueEnabled()).isTrue();
        assertThat(config.getCookieSpec()).isEqualTo("STRICT");
    }

    // -------------------------------------------------------------------------
    // createConnectionConfig
    // -------------------------------------------------------------------------

    @Test
    void testCreateConnectionConfigReturnsNonNull() {
        var fetcher = new HttpClientFetcher();
        assertThat(fetcher.createConnectionConfig()).isNotNull();
    }

    @Test
    void testCreateConnectionConfigWithConfiguredValues() {
        var fetcher = new HttpClientFetcher();
        fetcher.getConfiguration()
                .setConnectionTimeout(Duration.ofSeconds(2))
                .setSocketTimeout(Duration.ofSeconds(3))
                .setMaxConnectionInactiveTime(Duration.ofSeconds(4));

        var connectionConfig = fetcher.createConnectionConfig();

        assertThat(connectionConfig).isNotNull();
        assertThat(connectionConfig.getConnectTimeout()).isNotNull();
        assertThat(connectionConfig.getSocketTimeout()).isNotNull();
        assertThat(connectionConfig.getValidateAfterInactivity()).isNotNull();
    }

    // -------------------------------------------------------------------------
    // createConnectionManager
    // -------------------------------------------------------------------------

    @Test
    void testCreateConnectionManagerReturnsNonNull() {
        var fetcher = new HttpClientFetcher();
        assertThat(fetcher.createConnectionManager()).isNotNull();
    }

    @Test
    void testFetchReturnsNewForSuccessfulResponse() throws Exception {
        var fetcher = new HttpClientFetcher();
        setPrivateField(fetcher, "httpClient",
                mockHttpClientResponse(200, "OK"));

        var response = fetcher.fetch(new WebFetchRequest(
                CrawlDocStubs.crawlDocHtml("http://example.com"),
                HttpMethod.GET));

        assertThat(response.getProcessingOutcome()).isEqualTo(
                ProcessingOutcome.NEW);
    }

    @Test
    void testFetchReturnsUnmodifiedForNotModifiedResponse() throws Exception {
        var fetcher = new HttpClientFetcher();
        setPrivateField(fetcher, "httpClient",
                mockHttpClientResponse(304, "Not Modified"));

        var response = fetcher.fetch(new WebFetchRequest(
                CrawlDocStubs.crawlDocHtml("http://example.com"),
                HttpMethod.GET));

        assertThat(response.getProcessingOutcome()).isEqualTo(
                ProcessingOutcome.UNMODIFIED);
    }

    @Test
    void testFetchReturnsNotFoundForConfiguredStatus() throws Exception {
        var fetcher = new HttpClientFetcher();
        setPrivateField(fetcher, "httpClient",
                mockHttpClientResponse(404, "Not Found"));

        var response = fetcher.fetch(new WebFetchRequest(
                CrawlDocStubs.crawlDocHtml("http://example.com"),
                HttpMethod.GET));

        assertThat(response.getProcessingOutcome()).isEqualTo(
                ProcessingOutcome.NOT_FOUND);
    }

    @Test
    void testFetchReturnsBadStatusForUnexpectedResponse() throws Exception {
        var fetcher = new HttpClientFetcher();
        setPrivateField(fetcher, "httpClient",
                mockHttpClientResponse(500, "Internal Server Error"));

        var response = fetcher.fetch(new WebFetchRequest(
                CrawlDocStubs.crawlDocHtml("http://example.com"),
                HttpMethod.GET));

        assertThat(response.getProcessingOutcome()).isEqualTo(
                ProcessingOutcome.BAD_STATUS);
    }

    @Test
    void testFetchPostWithHstsDisabledReturnsNew() throws Exception {
        var fetcher = new HttpClientFetcher();
        fetcher.getConfiguration().setHstsDisabled(true);
        setPrivateField(fetcher, "httpClient",
                mockHttpClientResponse(200, "OK"));

        var response = fetcher.fetch(new WebFetchRequest(
                CrawlDocStubs.crawlDoc("http://example.com"),
                HttpMethod.POST));

        assertThat(response.getProcessingOutcome()).isEqualTo(
                ProcessingOutcome.NEW);
    }

    private static HttpClient mockHttpClientResponse(int statusCode,
            String reasonPhrase) throws Exception {
        var httpClient = mock(HttpClient.class);
        when(httpClient.execute(
                org.mockito.ArgumentMatchers.<HttpUriRequestBase>any(),
                org.mockito.ArgumentMatchers.<HttpClientContext>any(),
                org.mockito.ArgumentMatchers
                        .<HttpClientResponseHandler<WebFetchResponse>>any()))
                                .thenAnswer(invocation -> {
                                    @SuppressWarnings("unchecked")
                                    HttpClientResponseHandler<
                                            WebFetchResponse> handler =
                                                    invocation.getArgument(2);
                                    ClassicHttpResponse response =
                                            new BasicClassicHttpResponse(
                                                    statusCode, reasonPhrase);
                                    response.setEntity(new StringEntity(
                                            "<html>test</html>",
                                            StandardCharsets.UTF_8));
                                    response.addHeader("Content-Type",
                                            "text/html; charset=UTF-8");
                                    return handler.handleResponse(response);
                                });
        return httpClient;
    }

    private static void setPrivateField(Object target, String fieldName,
            Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
