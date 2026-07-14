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

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.norconex.crawler.fs.fetch.impl.ReadOnlyFileSystem;

import jcifs.CIFSContext;

/**
 * A read-only SMB (CIFS) file system, representing one authority
 * (host + port), backed by jcifs-ng.
 */
final class SmbFileSystem extends ReadOnlyFileSystem {

    private final SmbFileSystemProvider provider;
    private final String key;
    private final String host;
    private final int port;
    private final CIFSContext context;

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
        super("SMB");
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
        if (markClosed()) {
            provider.closeFileSystem(key);
        }
    }

    @Override
    public SmbPath getPath(String first, String... more) {
        return (SmbPath) super.getPath(first, more);
    }

    @Override
    protected Path createPath(String path) {
        return new SmbPath(this, path);
    }
}
