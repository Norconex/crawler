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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import com.norconex.crawler.fs.fetch.impl.ReadOnlyPath;

/**
 * A path within a {@link SmbFileSystem}. Always absolute (all SMB resource
 * paths are rooted at {@code /}, with the share name as the first
 * segment); relative-path support is limited to what {@link #resolve(Path)}
 * needs to build a child path from a path segment.
 */
final class SmbPath extends ReadOnlyPath {

    private final SmbFileSystem fs;

    SmbPath(SmbFileSystem fs, String path) {
        this(fs, ReadOnlyPath.parse(path));
    }

    private SmbPath(SmbFileSystem fs, List<String> segments) {
        super(fs, List.copyOf(segments), "SmbPath");
        this.fs = fs;
    }

    /** The normalized, absolute, slash-separated SMB resource path. */
    String path() {
        return normalizedPath();
    }

    @Override
    public SmbFileSystem getFileSystem() {
        return fs;
    }

    @Override
    protected Path createPath(List<String> segments) {
        return new SmbPath(fs, segments);
    }

    @Override
    public URI toUri() {
        try {
            return new URI(
                    "smb", null, fs.host(), fs.port(), path(), null, null);
        } catch (URISyntaxException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }
}
