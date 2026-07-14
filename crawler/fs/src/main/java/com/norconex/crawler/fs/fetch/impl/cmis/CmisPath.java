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

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import com.norconex.crawler.fs.fetch.impl.ReadOnlyPath;

/**
 * A path within a {@link CmisFileSystem}. Always absolute (all CMIS object
 * paths are rooted at {@code /}); relative-path support is limited to what
 * {@link #resolve(Path)} needs to build a child path from a path segment.
 */
final class CmisPath extends ReadOnlyPath {

    private final CmisFileSystem fs;

    CmisPath(CmisFileSystem fs, String path) {
        this(fs, ReadOnlyPath.parse(path));
    }

    private CmisPath(CmisFileSystem fs, List<String> segments) {
        super(fs, List.copyOf(segments), "CmisPath");
        this.fs = fs;
    }

    /** The normalized, absolute, slash-separated CMIS object path. */
    String path() {
        return normalizedPath();
    }

    @Override
    public CmisFileSystem getFileSystem() {
        return fs;
    }

    @Override
    protected Path createPath(List<String> segments) {
        return new CmisPath(fs, segments);
    }

    @Override
    public URI toUri() {
        return URI.create("cmis:" + fs.endpointUrl() + "!" + path());
    }
}
