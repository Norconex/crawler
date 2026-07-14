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
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.norconex.commons.lang.xml.Xml;
import com.norconex.crawler.fs.fetch.impl.ReadOnlyFileSystem;

/**
 * A read-only, single-repository CMIS Atom file system.
 */
final class CmisFileSystem extends ReadOnlyFileSystem {

    private final CmisFileSystemProvider provider;
    private final String endpointUrl;
    private final CmisAtomSession session;

    // Caches the entry document per CMIS object path so a single crawl
    // pass (existence/type check, metadata, content, and directory
    // listing) does not each re-issue the same Atom "object" GET request.
    private final Map<String, Xml> entryCache = new ConcurrentHashMap<>();

    CmisFileSystem(
            CmisFileSystemProvider provider, String endpointUrl,
            CmisAtomSession session) {
        super("CMIS");
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
        if (markClosed()) {
            provider.closeFileSystem(endpointUrl);
            session.close();
        }
    }

    @Override
    public CmisPath getPath(String first, String... more) {
        return (CmisPath) super.getPath(first, more);
    }

    @Override
    protected Path createPath(String path) {
        return new CmisPath(this, path);
    }
}
