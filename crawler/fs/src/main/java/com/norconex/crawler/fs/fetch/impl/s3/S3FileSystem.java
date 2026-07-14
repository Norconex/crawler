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
package com.norconex.crawler.fs.fetch.impl.s3;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.norconex.crawler.fs.fetch.impl.ReadOnlyFileSystem;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * A read-only S3 file system, representing one bucket (S3 buckets are
 * globally addressable, so a bucket name is a complete authority on its
 * own - no host/port needed).
 */
final class S3FileSystem extends ReadOnlyFileSystem {

    private final S3FileSystemProvider provider;
    private final String key;
    private final String bucket;
    private final S3Client client;

    // ListObjectsV2 returns attributes for every listed child in one round
    // trip. Caching them here lets the subsequent per-child
    // readAttributes() call (issued next by the crawler for each listed
    // child) be served without a redundant HeadObject round-trip.
    private final Map<String, S3FileAttributes> attrsCache =
            new ConcurrentHashMap<>();

    S3FileSystem(
            S3FileSystemProvider provider, String key, String bucket,
            S3Client client) {
        super("S3");
        this.provider = provider;
        this.key = key;
        this.bucket = bucket;
        this.client = client;
    }

    String bucket() {
        return bucket;
    }

    S3Client client() {
        return client;
    }

    Map<String, S3FileAttributes> attrsCache() {
        return attrsCache;
    }

    @Override
    public S3FileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() {
        if (markClosed()) {
            provider.closeFileSystem(key);
            client.close();
        }
    }

    @Override
    public S3Path getPath(String first, String... more) {
        return (S3Path) super.getPath(first, more);
    }

    @Override
    protected Path createPath(String path) {
        return new S3Path(this, path);
    }
}
