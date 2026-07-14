/* Copyright 2019-2026 Norconex Inc.
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;

import com.norconex.crawler.fs.fetch.impl.ReadOnlyFileSystem;

/**
 * A read-only WebDAV (or plain HTTP) file system, representing one
 * authority (scheme + host + port).
 */
final class WebDavFileSystem extends ReadOnlyFileSystem {

    private final WebDavFileSystemProvider provider;
    private final String key;
    private final String declaredScheme;
    private final String transportScheme;
    private final String host;
    private final int port;
    private final CloseableHttpClient httpClient;

    // Directory listings (PROPFIND, Depth: 1) return attributes for every
    // child in one request. Caching them here lets the subsequent
    // per-child readAttributes() call (issued next by the crawler for each
    // listed child) be served without a redundant PROPFIND, Depth: 0
    // round-trip.
    private final Map<String, WebDavFileAttributes> attrsCache =
            new ConcurrentHashMap<>();

    WebDavFileSystem(
            WebDavFileSystemProvider provider, String key,
            String declaredScheme, String transportScheme, String host,
            int port, CloseableHttpClient httpClient) {
        super("WebDAV");
        this.provider = provider;
        this.key = key;
        this.declaredScheme = declaredScheme;
        this.transportScheme = transportScheme;
        this.host = host;
        this.port = port;
        this.httpClient = httpClient;
    }

    String declaredScheme() {
        return declaredScheme;
    }

    String transportScheme() {
        return transportScheme;
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    CloseableHttpClient httpClient() {
        return httpClient;
    }

    Map<String, WebDavFileAttributes> attrsCache() {
        return attrsCache;
    }

    @Override
    public WebDavFileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() {
        if (markClosed()) {
            provider.closeFileSystem(key);
            try {
                httpClient.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public WebDavPath getPath(String first, String... more) {
        return (WebDavPath) super.getPath(first, more);
    }

    @Override
    protected Path createPath(String path) {
        return new WebDavPath(this, path);
    }
}
