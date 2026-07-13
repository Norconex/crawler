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
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;

/**
 * A read-only WebDAV (or plain HTTP) file system, representing one
 * authority (scheme + host + port).
 */
final class WebDavFileSystem extends FileSystem {

    private final WebDavFileSystemProvider provider;
    private final String key;
    private final String declaredScheme;
    private final String transportScheme;
    private final String host;
    private final int port;
    private final CloseableHttpClient httpClient;
    private final AtomicBoolean open = new AtomicBoolean(true);

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
        if (open.compareAndSet(true, false)) {
            provider.closeFileSystem(key);
            try {
                httpClient.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getSeparator() {
        return "/";
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return Set.of(getPath("/"));
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return Set.of();
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return Set.of("basic");
    }

    @Override
    public WebDavPath getPath(String first, String... more) {
        var path = first;
        for (var part : more) {
            path += "/" + part;
        }
        return new WebDavPath(this, path);
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        var sep = syntaxAndPattern.indexOf(':');
        if (sep <= 0) {
            throw new IllegalArgumentException(
                    "Invalid syntax and pattern: " + syntaxAndPattern);
        }
        var syntax = syntaxAndPattern.substring(0, sep);
        var pattern = syntaxAndPattern.substring(sep + 1);
        if (!"glob".equalsIgnoreCase(syntax)) {
            throw new UnsupportedOperationException(
                    "Unsupported path matcher syntax: " + syntax);
        }
        var regex = Pattern.compile(
                pattern
                        .replace(".", "\\.")
                        .replace("*", ".*")
                        .replace("?", "."));
        return p -> regex.matcher(p.toString()).matches();
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException(
                "WebDAV file systems do not support user principal lookup.");
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException(
                "WebDAV file systems do not support watching.");
    }
}
