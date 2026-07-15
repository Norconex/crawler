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
package com.norconex.crawler.web.fetch.impl.webdriver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.LinkedList;
import java.util.Queue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.littleshoot.proxy.ChainedProxy;
import org.littleshoot.proxy.impl.ClientDetails;
import org.openqa.selenium.chrome.ChromeOptions;

import com.norconex.commons.lang.net.Host;
import com.norconex.commons.lang.net.ProxySettings;
import com.norconex.commons.lang.security.Credentials;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

@Timeout(30)
class HttpSnifferTest {

        @Test
        void getConfigurationReturnsNonNull() {
                assertThat(new HttpSniffer().getConfiguration()).isNotNull();
        }

        @Test
        void trackReturnsPendingFuture() {
                var sniffer = new HttpSniffer();
                var future = sniffer.track("req-1");
                assertThat(future).isNotNull();
                assertThat(future.isDone()).isFalse();
        }

        @Test
        void trackFutureCompletesSuccessfully() {
                var sniffer = new HttpSniffer();
                var requestId = "req-complete";
                var future = sniffer.track(requestId);

                var mockHeaders = mock(
                                io.netty.handler.codec.http.HttpHeaders.class);
                var sniffed = new HttpSniffer.SniffedResponseHeaders(
                                requestId, HttpResponseStatus.OK, mockHeaders,
                                "Agent/1.0");
                future.complete(sniffed);

                assertThat(future.isDone()).isTrue();
                assertThat(future.join()).isSameAs(sniffed);
        }

        @Test
        void sniffedResponseHeadersStoresAllFields() {
                var headers = mock(
                                io.netty.handler.codec.http.HttpHeaders.class);
                var shr = new HttpSniffer.SniffedResponseHeaders(
                                "req-2", HttpResponseStatus.NOT_FOUND, headers,
                                "TestAgent");

                assertThat(shr.getRequestId()).isEqualTo("req-2");
                assertThat(shr.getStatus())
                                .isEqualTo(HttpResponseStatus.NOT_FOUND);
                assertThat(shr.getHeaders()).isSameAs(headers);
                assertThat(shr.getRequestUserAgent()).isEqualTo("TestAgent");
        }

        @Test
        void chainedProxiesAddsAdapterWithCorrectAddress() {
                var settings = new ProxySettings();
                settings.setHost(new Host("proxy.example.com", 3128));
                settings.setCredentials(new Credentials("user", "pass"));

                var sniffer = new HttpSniffer();
                var chainedProxies = sniffer.new ChainedProxies(settings);

                Queue<ChainedProxy> queue = new LinkedList<>();
                chainedProxies.lookupChainedProxies(
                                mock(HttpRequest.class), queue,
                                mock(ClientDetails.class));

                assertThat(queue).hasSize(1);
                var adapter = queue.poll();
                assertThat(adapter.getChainedProxyAddress())
                                .satisfies(addr -> {
                                        assertThat(addr.getHostName())
                                                        .isEqualTo("proxy.example.com");
                                        assertThat(addr.getPort())
                                                        .isEqualTo(3128);
                                });
                assertThat(adapter.getUsername()).isEqualTo("user");
        }

        @Test
        void sniffingFilterReturnsConfiguredBufferSizes() {
                var sniffer = new HttpSniffer();
                sniffer.getConfiguration().setMaxBufferSize(8192);
                var filter = sniffer.new SniffingHttpFilter();

                assertThat(filter.getMaximumRequestBufferSizeInBytes())
                                .isEqualTo(8192);
                assertThat(filter.getMaximumResponseBufferSizeInBytes())
                                .isEqualTo(8192);
        }

        @Test
        void sniffingFilterCompletesTrackedFutureOnResponse() {
                var sniffer = new HttpSniffer();
                var requestId = "tracked-req";
                var future = sniffer.track(requestId);

                var filter = sniffer.new SniffingHttpFilter();
                var httpRequest = new DefaultHttpRequest(
                                HttpVersion.HTTP_1_1,
                                HttpMethod.GET,
                                "http://example.com/page?"
                                                + HttpSniffer.PARAM_REQUEST_ID
                                                + "=" + requestId);

                var filters = filter.filterRequest(httpRequest, null);
                // sets requestId on the inner adapter
                filters.clientToProxyRequest(httpRequest);

                // simulate server response
                var httpResponse = new DefaultHttpResponse(
                                HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                filters.serverToProxyResponse(httpResponse);

                assertThat(future.isDone()).isTrue();
                assertThat(future.join().getStatus())
                                .isEqualTo(HttpResponseStatus.OK);
        }

        @Test
        void sniffingFilterClientToProxyRequest_noRequestId_returnsNull() {
                var sniffer = new HttpSniffer();
                var filter = sniffer.new SniffingHttpFilter();
                var httpRequest = new DefaultHttpRequest(
                                HttpVersion.HTTP_1_1, HttpMethod.GET,
                                "http://example.com/page");

                var filters = filter.filterRequest(httpRequest, null);
                var result = filters.clientToProxyRequest(httpRequest);

                assertThat(result).isNull();
        }

        @Test
        void sniffingFilterServerToProxyResponse_noRequestId_returnsHttpObj() {
                var sniffer = new HttpSniffer();
                var filter = sniffer.new SniffingHttpFilter();
                var httpRequest = new DefaultHttpRequest(
                                HttpVersion.HTTP_1_1, HttpMethod.GET,
                                "http://example.com/page");

                var filters = filter.filterRequest(httpRequest, null);
                // No clientToProxyRequest call => requestId is null
                var httpResponse = new DefaultHttpResponse(
                                HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

                var result = filters.serverToProxyResponse(httpResponse);
                assertThat(result).isSameAs(httpResponse);
        }

        @Test
        void sniffingFilterAppliesCustomRequestHeaders() {
                var sniffer = new HttpSniffer();
                sniffer.getConfiguration().getRequestHeaders().put("X-Custom",
                                "value");
                var filter = sniffer.new SniffingHttpFilter();

                var requestId = "custom-headers-req";
                var httpRequest = new DefaultHttpRequest(
                                HttpVersion.HTTP_1_1,
                                HttpMethod.GET,
                                "http://example.com/?"
                                                + HttpSniffer.PARAM_REQUEST_ID
                                                + "="
                                                + requestId);

                var filters = filter.filterRequest(httpRequest, null);
                filters.clientToProxyRequest(httpRequest);

                assertThat(httpRequest.headers().get("X-Custom"))
                                .isEqualTo("value");
        }

        @Test
        void sniffingFilterOverridesUserAgent() {
                var sniffer = new HttpSniffer();
                sniffer.getConfiguration().setUserAgent("CrawlerBot/1.0");
                var filter = sniffer.new SniffingHttpFilter();

                var requestId = "ua-req";
                var httpRequest = new DefaultHttpRequest(
                                HttpVersion.HTTP_1_1,
                                HttpMethod.GET,
                                "http://example.com/?"
                                                + HttpSniffer.PARAM_REQUEST_ID
                                                + "="
                                                + requestId);
                httpRequest.headers().set("User-Agent", "OriginalAgent/1.0");

                var filters = filter.filterRequest(httpRequest, null);
                filters.clientToProxyRequest(httpRequest);

                assertThat(httpRequest.headers().get("User-Agent"))
                                .isEqualTo("CrawlerBot/1.0");
        }

        @Test
        void startStopAndConfigureBrowserUsesProxyHost() throws Exception {
                var sniffer = new HttpSniffer();
                sniffer.getConfiguration().setHost("127.0.0.1");
                sniffer.getConfiguration().setPort(0);

                try {
                        sniffer.start();
                        assertThat(sniffer.getAddress()).isNotNull();

                        var options = new ChromeOptions();
                        sniffer.configureBrowser(WebDriverBrowser.CHROME,
                                        options);
                        assertThat(options.asMap()).isNotEmpty();
                } finally {
                        try {
                                sniffer.stop();
                        } catch (Exception ignored) {
                                // Ignore shutdown issues from a partially started proxy.
                        }
                }
        }

        @Test
        void chainedProxiesReturnsDecryptedPasswordWhenConfigured()
                        throws Exception {
                var settings = new ProxySettings();
                settings.setHost(new Host("proxy.example.com", 3128));
                settings.setCredentials(
                                new Credentials("user", "plain-password"));

                var sniffer = new HttpSniffer();
                var chainedProxies = sniffer.new ChainedProxies(settings);

                Queue<ChainedProxy> queue = new LinkedList<>();
                chainedProxies.lookupChainedProxies(
                                mock(HttpRequest.class), queue,
                                mock(ClientDetails.class));

                var adapter = queue.poll();
                assertThat(adapter).isNotNull();
                assertThat(adapter.getPassword()).isEqualTo("plain-password");
        }
}
