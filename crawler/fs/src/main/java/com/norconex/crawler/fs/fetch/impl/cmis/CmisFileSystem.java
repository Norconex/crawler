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
package com.norconex.crawler.fs.fetch.impl.cmis;

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

import com.norconex.commons.lang.xml.Xml;

/**
 * A read-only, single-repository CMIS Atom file system.
 */
final class CmisFileSystem extends FileSystem {

    private final CmisFileSystemProvider provider;
    private final String endpointUrl;
    private final CmisAtomSession session;
    private final AtomicBoolean open = new AtomicBoolean(true);

    // Caches the entry document per CMIS object path so a single crawl
    // pass (existence/type check, metadata, content, and directory
    // listing) does not each re-issue the same Atom "object" GET request.
    private final Map<String, Xml> entryCache = new ConcurrentHashMap<>();

    CmisFileSystem(
            CmisFileSystemProvider provider, String endpointUrl,
            CmisAtomSession session) {
        this.provider = provider;
        this.endpointUrl = endpointUrl;
        this.session = session;
    }

    String endpointUrl() {
        return endpointUrl;
    }

    CmisAtomSession session() {
        return session;
    }

    Xml entry(String path) throws IOException {
        var cached = entryCache.get(path);
        if (cached != null) {
            return cached;
        }
        var doc = session.getDocumentByPath(path);
        entryCache.put(path, doc);
        return doc;
    }

    @Override
    public CmisFileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() {
        if (open.compareAndSet(true, false)) {
            provider.closeFileSystem(endpointUrl);
            session.close();
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
    public CmisPath getPath(String first, String... more) {
        var path = first;
        for (var part : more) {
            path += "/" + part;
        }
        return new CmisPath(this, path);
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
                "CMIS file systems do not support user principal lookup.");
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException(
                "CMIS file systems do not support watching.");
    }
}
