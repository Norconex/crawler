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
package com.norconex.crawler.fs.fetch.impl.azureblob;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.azure.storage.blob.BlobContainerClient;
import com.norconex.crawler.fs.fetch.impl.ReadOnlyFileSystem;

/**
 * A read-only Azure Blob file system representing one blob container.
 */
final class AzureBlobFileSystem extends ReadOnlyFileSystem {

    private final AzureBlobFileSystemProvider provider;
    private final String key;
    private final String scheme;
    private final String account;
    private final String container;
    private final BlobContainerClient client;
    private final Map<String, AzureBlobFileAttributes> attrsCache =
            new ConcurrentHashMap<>();

    AzureBlobFileSystem(
            AzureBlobFileSystemProvider provider, AzureBlobLocation location,
            BlobContainerClient client) {
        super("Azure Blob");
        this.provider = provider;
        key = location.key();
        scheme = location.scheme();
        account = location.account();
        container = location.container();
        this.client = client;
    }

    String scheme() {
        return scheme;
    }

    String account() {
        return account;
    }

    String container() {
        return container;
    }

    BlobContainerClient client() {
        return client;
    }

    Map<String, AzureBlobFileAttributes> attrsCache() {
        return attrsCache;
    }

    @Override
    public AzureBlobFileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() {
        if (markClosed()) {
            provider.closeFileSystem(key);
        }
    }

    @Override
    public AzureBlobPath getPath(String first, String... more) {
        return (AzureBlobPath) super.getPath(first, more);
    }

    @Override
    protected Path createPath(String path) {
        return new AzureBlobPath(this, path);
    }
}
