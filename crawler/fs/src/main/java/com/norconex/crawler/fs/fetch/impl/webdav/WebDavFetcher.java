/* Copyright 2023-2026 Norconex Inc.
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
package com.norconex.crawler.fs.fetch.impl.webdav;

import static com.norconex.commons.lang.encrypt.EncryptionUtil.decrypt;
import static com.norconex.commons.lang.encrypt.EncryptionUtil.decryptPassword;
import static com.norconex.crawler.fs.fetch.impl.FileFetchUtil.referenceStartsWith;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.ssl.SSLContexts;

import com.norconex.crawler.core.fetch.FetchRequest;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.fs.fetch.impl.AbstractNioFetcher;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Fetcher for WebDAV repositories (<code>webdav://</code>,
 * <code>webdavs://</code>), backed by a custom, read-only NIO.2
 * {@link java.nio.file.spi.FileSystemProvider}
 * ({@link WebDavFileSystemProvider}) that speaks WebDAV directly over
 * Apache HttpClient (no third-party WebDAV library).
 * </p>
 * <p>
 * It can also be pointed at plain <code>http://</code>/<code>https://</code>
 * resources, in which case each reference is treated as a single fetchable
 * file (no directory traversal). For crawling web sites, use the Norconex
 * Web Crawler instead.
 * </p>
 */
@Slf4j
@ToString
@EqualsAndHashCode
public class WebDavFetcher extends AbstractNioFetcher<WebDavFetcherConfig> {

    @Getter
    private final WebDavFetcherConfig configuration = new WebDavFetcherConfig();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final WebDavFileSystemProvider provider =
            new WebDavFileSystemProvider();

    @Override
    protected void fetcherShutdown(CrawlerSession crawler) {
        provider.openFileSystems().forEach(fs -> {
            try {
                fs.close();
            } catch (RuntimeException e) {
                LOG.warn("Could not close WebDAV file system.", e);
            }
        });
        super.fetcherShutdown(crawler);
    }

    @Override
    protected boolean acceptRequest(@NonNull FetchRequest fetchRequest) {
        return referenceStartsWith(fetchRequest,
                "webdav://", "webdavs://", "http://", "https://");
    }

    @Override
    protected Path resolvePath(String reference) throws IOException {
        var uri = URI.create(reference);
        var fs = provider.getOrCreateFileSystem(uri, buildEnv());
        return fs.getPath(StringUtils.defaultIfBlank(uri.getPath(), "/"));
    }

    private Map<String, Object> buildEnv() {
        Map<String, Object> env = new HashMap<>();
        // Each authority gets its own client (and connection pool), created
        // lazily and owned/closed by its WebDavFileSystem.
        Supplier<CloseableHttpClient> clientSupplier = this::buildHttpClient;
        env.put(
                WebDavFileSystemProvider.ENV_CLIENT_SUPPLIER, clientSupplier);
        return env;
    }

    // Builds an HttpClient configured from this fetcher's settings. Called
    // once per target authority by the provider. Package-private so config
    // wiring (proxy, TLS, ...) can be exercised in tests without a live
    // server.
    CloseableHttpClient buildHttpClient() {
        var cfg = configuration;
        var builder = HttpClientBuilder.create();

        if (StringUtils.isNotBlank(cfg.getUserAgent())) {
            builder.setUserAgent(cfg.getUserAgent());
        }

        // Authentication
        var creds = cfg.getCredentials();
        if (creds.isSet()) {
            var credsProvider = new BasicCredentialsProvider();
            credsProvider.setCredentials(
                    AuthScope.ANY,
                    new UsernamePasswordCredentials(
                            creds.getUsername(), decryptPassword(creds)));
            builder.setDefaultCredentialsProvider(credsProvider);
        }

        // Connection pool limits
        builder.setMaxConnPerRoute(cfg.getMaxConnectionsPerHost());
        builder.setMaxConnTotal(cfg.getMaxTotalConnections());

        // Timeouts + redirect handling
        var requestConfig = RequestConfig.custom();
        if (cfg.getConnectionTimeout() != null) {
            requestConfig.setConnectTimeout(
                    (int) cfg.getConnectionTimeout().toMillis());
        }
        if (cfg.getSoTimeout() != null) {
            requestConfig.setSocketTimeout(
                    (int) cfg.getSoTimeout().toMillis());
        }
        requestConfig.setRedirectsEnabled(cfg.isFollowRedirect());
        builder.setDefaultRequestConfig(requestConfig.build());

        if (!cfg.isKeepAlive()) {
            builder.setConnectionReuseStrategy((response, ctx) -> false);
        }

        // Proxy
        if (cfg.getProxySettings().isSet()) {
            var proxyHost = cfg.getProxySettings().getHost();
            builder.setProxy(new HttpHost(
                    proxyHost.getName(), proxyHost.getPort(),
                    cfg.getProxySettings().getScheme()));
            var proxyCreds = cfg.getProxySettings().getCredentials();
            if (proxyCreds.isSet()) {
                var credsProvider = new BasicCredentialsProvider();
                credsProvider.setCredentials(
                        new AuthScope(
                                proxyHost.getName(), proxyHost.getPort()),
                        new UsernamePasswordCredentials(
                                proxyCreds.getUsername(),
                                decryptPassword(proxyCreds)));
                builder.setDefaultCredentialsProvider(credsProvider);
            }
        }

        // TLS
        builder.setSSLSocketFactory(buildSslSocketFactory());

        return builder.build();
    }

    private SSLConnectionSocketFactory buildSslSocketFactory() {
        var cfg = configuration;
        try {
            var sslBuilder = SSLContexts.custom();
            if (StringUtils.isNotBlank(cfg.getKeyStoreFile())) {
                var keyStore = KeyStore.getInstance(
                        StringUtils.defaultIfBlank(
                                cfg.getKeyStoreType(),
                                KeyStore.getDefaultType()));
                var pass = decrypt(
                        cfg.getKeyStorePass(), cfg.getKeyStorePassKey());
                var passChars = pass == null
                        ? new char[0]
                        : pass.toCharArray();
                try (var in = java.nio.file.Files.newInputStream(
                        java.nio.file.Path.of(cfg.getKeyStoreFile()))) {
                    keyStore.load(in, passChars);
                }
                sslBuilder.loadKeyMaterial(keyStore, passChars);
            }
            var sslContext = sslBuilder.build();

            String[] protocols = null;
            if (StringUtils.isNotBlank(cfg.getTlsVersions())) {
                protocols = StringUtils.split(cfg.getTlsVersions(), ", ");
            }
            var hostnameVerifier = cfg.isHostnameVerificationEnabled()
                    ? SSLConnectionSocketFactory.getDefaultHostnameVerifier()
                    : NoopHostnameVerifier.INSTANCE;
            return new SSLConnectionSocketFactory(
                    sslContext, protocols, null, hostnameVerifier);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not configure WebDAV TLS settings.", e);
        }
    }

    @ToString.Include(name = "keyStorePass")
    private String keyStorePassToString() {
        return "********";
    }
}
