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
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.security.auth.Subject;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;

import com.norconex.crawler.fs.fetch.impl.ReadOnlyFileSystem;

/**
 * A read-only HDFS file system, reached over the WebHDFS REST API,
 * representing one authority (host + port).
 */
final class HdfsFileSystem extends ReadOnlyFileSystem {

    private final HdfsFileSystemProvider provider;
    private final String key;
    private final String host;
    private final int port;
    private final String username;
    private final Subject kerberosSubject;
    private final CloseableHttpClient httpClient;

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
        super("HDFS");
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
        if (markClosed()) {
            provider.closeFileSystem(key);
            try {
                httpClient.close();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }

    @Override
    public HdfsPath getPath(String first, String... more) {
        return (HdfsPath) super.getPath(first, more);
    }

    @Override
    protected Path createPath(String path) {
        return new HdfsPath(this, path);
    }
}
