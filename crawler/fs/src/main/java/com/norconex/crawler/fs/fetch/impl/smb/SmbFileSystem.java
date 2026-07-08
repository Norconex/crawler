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
package com.norconex.crawler.fs.fetch.impl.smb;

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

import jcifs.CIFSContext;

/**
 * A read-only SMB (CIFS) file system, representing one authority
 * (host + port), backed by jcifs-ng.
 */
final class SmbFileSystem extends FileSystem {

    private final SmbFileSystemProvider provider;
    private final String key;
    private final String host;
    private final int port;
    private final CIFSContext context;
    private final AtomicBoolean open = new AtomicBoolean(true);

    // Directory listings enumerate every child's attributes in one round
    // trip (jcifs-ng populates them from the FIND response). Caching them
    // here lets the subsequent per-child readAttributes() call (issued
    // next by the crawler for each listed child) be served without a
    // redundant stat round-trip.
    private final Map<String, SmbFileAttributes> attrsCache =
            new ConcurrentHashMap<>();

    SmbFileSystem(
            SmbFileSystemProvider provider, String key, String host,
            int port, CIFSContext context) {
        this.provider = provider;
        this.key = key;
        this.host = host;
        this.port = port;
        this.context = context;
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    CIFSContext context() {
        return context;
    }

    Map<String, SmbFileAttributes> attrsCache() {
        return attrsCache;
    }

    @Override
    public SmbFileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() {
        if (open.compareAndSet(true, false)) {
            provider.closeFileSystem(key);
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
    public SmbPath getPath(String first, String... more) {
        var path = first;
        for (var part : more) {
            path += "/" + part;
        }
        return new SmbPath(this, path);
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
                "SMB file systems do not support user principal lookup.");
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException(
                "SMB file systems do not support watching.");
    }
}
