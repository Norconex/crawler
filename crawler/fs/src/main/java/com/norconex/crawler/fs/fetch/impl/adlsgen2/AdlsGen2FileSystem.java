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
package com.norconex.crawler.fs.fetch.impl.adlsgen2;

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

import com.azure.storage.file.datalake.DataLakeFileSystemClient;
import com.norconex.commons.lang.map.Properties;

/**
 * A read-only ADLS Gen2 file system representing one file system in one
 * storage account.
 */
final class AdlsGen2FileSystem extends FileSystem {

    private final AdlsGen2FileSystemProvider provider;
    private final String key;
    private final String scheme;
    private final String fileSystemName;
    private final String account;
    private final String host;
    private final DataLakeFileSystemClient client;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Map<String, AdlsGen2FileAttributes> attrsCache =
            new ConcurrentHashMap<>();
    private final Map<String, Properties> aclCache = new ConcurrentHashMap<>();

    AdlsGen2FileSystem(
            AdlsGen2FileSystemProvider provider, AdlsGen2Location location,
            DataLakeFileSystemClient client) {
        this.provider = provider;
        key = location.key();
        scheme = location.scheme();
        fileSystemName = location.fileSystem();
        account = location.account();
        host = location.host();
        this.client = client;
    }

    String scheme() {
        return scheme;
    }

    String fileSystemName() {
        return fileSystemName;
    }

    String account() {
        return account;
    }

    String host() {
        return host;
    }

    DataLakeFileSystemClient client() {
        return client;
    }

    Map<String, AdlsGen2FileAttributes> attrsCache() {
        return attrsCache;
    }

    Map<String, Properties> aclCache() {
        return aclCache;
    }

    @Override
    public AdlsGen2FileSystemProvider provider() {
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
    public AdlsGen2Path getPath(String first, String... more) {
        var path = first;
        for (var part : more) {
            path += "/" + part;
        }
        return new AdlsGen2Path(this, path);
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
                pattern.replace(".", "\\.").replace("*", ".*")
                        .replace("?", "."));
        return p -> regex.matcher(p.toString()).matches();
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException(
                "ADLS Gen2 file systems do not support principal lookup.");
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException(
                "ADLS Gen2 file systems do not support watching.");
    }
}
