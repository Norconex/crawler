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

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.azure.storage.file.datalake.DataLakeFileSystemClient;
import com.norconex.commons.lang.map.Properties;
import com.norconex.crawler.fs.fetch.impl.ReadOnlyFileSystem;

/**
 * A read-only ADLS Gen2 file system representing one file system in one
 * storage account.
 */
final class AdlsGen2FileSystem extends ReadOnlyFileSystem {

    private final AdlsGen2FileSystemProvider provider;
    private final String key;
    private final String scheme;
    private final String fileSystemName;
    private final String account;
    private final String host;
    private final DataLakeFileSystemClient client;
    private final Map<String, AdlsGen2FileAttributes> attrsCache =
            new ConcurrentHashMap<>();
    private final Map<String, Properties> aclCache = new ConcurrentHashMap<>();

    AdlsGen2FileSystem(
            AdlsGen2FileSystemProvider provider, AdlsGen2Location location,
            DataLakeFileSystemClient client) {
        super("ADLS Gen2");
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
        if (markClosed()) {
            provider.closeFileSystem(key);
        }
    }

    @Override
    public AdlsGen2Path getPath(String first, String... more) {
        return (AdlsGen2Path) super.getPath(first, more);
    }

    @Override
    protected Path createPath(String path) {
        return new AdlsGen2Path(this, path);
    }
}
