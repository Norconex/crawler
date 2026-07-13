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
package com.norconex.crawler.fs.fetch.impl.hdfs;

import java.io.IOException;
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

import javax.security.auth.Subject;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;

/**
 * A read-only HDFS file system, reached over the WebHDFS REST API,
 * representing one authority (host + port).
 */
final class HdfsFileSystem extends FileSystem {

    private final HdfsFileSystemProvider provider;
    private final String key;
    private final String host;
    private final int port;
    private final String username;
    private final Subject kerberosSubject;
    private final CloseableHttpClient httpClient;
    private final AtomicBoolean open = new AtomicBoolean(true);

    // GETFILESTATUS/LISTSTATUS return attributes for every listed child in
    // one round trip. Caching them here lets the subsequent per-child
    // readAttributes() call (issued next by the crawler for each listed
    // child) be served without a redundant round-trip.
    private final Map<String, HdfsFileAttributes> attrsCache =
            new ConcurrentHashMap<>();

    HdfsFileSystem(
            HdfsFileSystemProvider provider, String key, String host,
            int port, String username, Subject kerberosSubject,
            CloseableHttpClient httpClient) {
        this.provider = provider;
        this.key = key;
        this.host = host;
        this.port = port;
        this.username = username;
        this.kerberosSubject = kerberosSubject;
        this.httpClient = httpClient;
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    String username() {
        return username;
    }

    Subject kerberosSubject() {
        return kerberosSubject;
    }

    CloseableHttpClient httpClient() {
        return httpClient;
    }

    Map<String, HdfsFileAttributes> attrsCache() {
        return attrsCache;
    }

    @Override
    public HdfsFileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() {
        if (open.compareAndSet(true, false)) {
            provider.closeFileSystem(key);
            try {
                httpClient.close();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
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
    public HdfsPath getPath(String first, String... more) {
        var path = first;
        for (var part : more) {
            path += "/" + part;
        }
        return new HdfsPath(this, path);
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
                "HDFS file systems do not support user principal lookup.");
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException(
                "HDFS file systems do not support watching.");
    }
}
