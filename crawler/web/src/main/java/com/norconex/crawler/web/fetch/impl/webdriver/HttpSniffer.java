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

import static io.netty.handler.codec.http.HttpHeaderNames.USER_AGENT;
import static java.util.Optional.ofNullable;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.littleshoot.proxy.ChainedProxy;
import org.littleshoot.proxy.ChainedProxyAdapter;
import org.littleshoot.proxy.ChainedProxyManager;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersAdapter;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.extras.SelfSignedMitmManager;
import org.littleshoot.proxy.extras.SelfSignedSslEngineSource;
import org.littleshoot.proxy.impl.ClientDetails;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.openqa.selenium.MutableCapabilities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.norconex.commons.lang.config.Configurable;
import com.norconex.commons.lang.encrypt.EncryptionUtil;
import com.norconex.commons.lang.net.Host;
import com.norconex.commons.lang.net.ProxySettings;
import com.norconex.commons.lang.url.HttpURL;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Used to set and capture HTTP request/response headers, when enabled.
 * </p>
 * <p>
 * <b>EXPERIMENTAL:</b> The use of this class is experimental.
 * It is known to not be supported properly with some web drivers and/or
 * browsers. It can even be ignored altogether by some web drivers.
 * </p>
 *
 * @since 3.0.0
 */
@Slf4j
@EqualsAndHashCode
@ToString
public class HttpSniffer implements Configurable<HttpSnifferConfig> {

    public static final String PARAM_REQUEST_ID = "crawlRequestId";
    public static final String HEADER_REQUEST_ID = "X-Crawl-Request-Id";

    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private final Cache<String,
            CompletableFuture<SniffedResponseHeaders>> sniffedHeaders =
                    CacheBuilder.newBuilder()
                            .expireAfterWrite(Duration.ofMinutes(5))
                            .build();

    @Getter
    private final HttpSnifferConfig configuration = new HttpSnifferConfig();
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private HttpProxyServer proxyServer;
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Host proxyHost;

    public void start() {
        var serverBootstrap = DefaultHttpProxyServer.bootstrap()
                .withAddress(new InetSocketAddress(
                        ofNullable(configuration.getNetworkInterface())
                                .orElse("0.0.0.0"), // binds to all
                        configuration.getPort()))
                .withAllowLocalOnly(false)
                .withFiltersSource(new SniffingHttpFilter())
                .withManInTheMiddle(new SelfSignedMitmManager(
                        new SelfSignedSslEngineSource(
                                true, /* trustAllServers= */
                                true)));/* sendCerts= */

        // chained proxy
        if (configuration.getChainedProxy().isSet()) {
            serverBootstrap.withChainProxyManager(
                    new ChainedProxies(configuration.getChainedProxy()));
        } else {
            LOG.info("No chained proxy configured on SniffingProxy.");
        }

        proxyServer = serverBootstrap.start();
        proxyHost = new Host(
                configuration.getHost(),
                proxyServer.getListenAddress().getPort());
        LOG.info("SnifferProxy started as: {}.", proxyHost);
    }

    public void stop() {
        proxyServer.stop();
    }

    @JsonIgnore
    public InetSocketAddress getAddress() {
        return new InetSocketAddress(proxyHost.getName(), proxyHost.getPort());
    }

    public CompletableFuture<SniffedResponseHeaders>
            track(@NonNull String requestId) {
        var future = new CompletableFuture<SniffedResponseHeaders>();
        sniffedHeaders.put(requestId, future);
        future.whenComplete((res, ex) -> sniffedHeaders.invalidate(requestId));
        return future;
    }

    public void configureBrowser(WebDriverBrowser browser,
            MutableCapabilities opts) {
        browser.configureProxy(opts, proxyHost);
    }

    /**
     * Handles proxy chaining
     */
    class ChainedProxies implements ChainedProxyManager {
        private final ProxySettings settings;

        ChainedProxies(ProxySettings proxySettings) {
            settings = proxySettings;
        }

        @Override
        public void lookupChainedProxies(
                HttpRequest httpRequest,
                Queue<ChainedProxy> chainedProxies,
                ClientDetails clientDetails) {
            chainedProxies.add(new ChainedProxyAdapter() {
                @Override
                public InetSocketAddress getChainedProxyAddress() {
                    var addr = new InetSocketAddress(
                            settings.getHost().getName(),
                            settings.getHost().getPort());
                    LOG.info("Chained proxy set on SniffingProxy: {}.", addr);
                    return addr;
                }

                @Override
                public String getUsername() {
                    return settings.getCredentials().getUsername();
                }

                @Override
                public String getPassword() {
                    LOG.info("Chained proxy credentials set.");
                    if (settings.getCredentials().isSet()) {
                        return EncryptionUtil.decryptPassword(
                                settings.getCredentials());
                    }
                    return null;
                }
            });
        }
    }

    /**
     * Filters requests and responses to intercept HTTP headers
     */
    class SniffingHttpFilter extends HttpFiltersSourceAdapter {
        @Override
        public HttpFilters filterRequest(
                HttpRequest originalRequest, ChannelHandlerContext ctx) {
            return new HttpFiltersAdapter(originalRequest) {
                private String requestId;
                private String browserAgent;

                @Override
                public HttpResponse clientToProxyRequest(HttpObject httpObj) {
                    if (httpObj instanceof HttpRequest request) {
                        LOG.trace("Sniffer incoming request: {}",
                                request.uri());
                        var decoder = new QueryStringDecoder(request.uri());
                        var ids = decoder.parameters().get(PARAM_REQUEST_ID);
                        if (CollectionUtils.isNotEmpty(ids)) {
                            var url = new HttpURL(request.uri());
                            requestId = ids.get(0);

                            url.getQueryString().remove(PARAM_REQUEST_ID);
                            request.headers().set(HEADER_REQUEST_ID, requestId);

                            // Add custom request headers
                            configuration
                                    .getRequestHeaders()
                                    .entrySet()
                                    .forEach(en -> request.headers().add(
                                            en.getKey(),
                                            en.getValue()));
                            browserAgent = request.headers().get(USER_AGENT);
                            // Overwrite with custom user agent
                            if (StringUtils.isNotBlank(
                                    configuration.getUserAgent())) {
                                request.headers().remove(USER_AGENT);
                                request.headers().add(
                                        USER_AGENT,
                                        configuration.getUserAgent());
                            }

                            request.setUri(url.toString());
                        }
                    }
                    return null; // continue chain
                }

                @Override
                public HttpObject serverToProxyResponse(HttpObject httpObj) {
                    if (httpObj instanceof HttpResponse response
                            && requestId != null) {
                        var future = sniffedHeaders.getIfPresent(requestId);
                        var header = new SniffedResponseHeaders(
                                requestId,
                                response.status(),
                                response.headers(),
                                browserAgent);
                        if (future != null) {
                            future.complete(header);
                        }
                    }
                    return httpObj;
                }
            };
        }

        @Override
        public int getMaximumRequestBufferSizeInBytes() {
            return configuration.getMaxBufferSize();
        }

        @Override
        public int getMaximumResponseBufferSizeInBytes() {
            return configuration.getMaxBufferSize();
        }
    }

    @Data
    @Accessors(chain = true)
    @Getter
    static class SniffedResponseHeaders {
        private final String requestId;
        private final HttpResponseStatus status;
        private final HttpHeaders headers;
        private final String requestUserAgent;
    }
}
