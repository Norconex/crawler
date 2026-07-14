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
package com.norconex.crawler.fs.fetch.impl.webdav;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import com.norconex.crawler.fs.fetch.impl.ReadOnlyPath;

/**
 * A path within a {@link WebDavFileSystem}. Always absolute (all WebDAV
 * resource paths are rooted at {@code /}); relative-path support is
 * limited to what {@link #resolve(Path)} needs to build a child path from
 * a path segment.
 */
final class WebDavPath extends ReadOnlyPath {

    private final WebDavFileSystem fs;

    WebDavPath(WebDavFileSystem fs, String path) {
        this(fs, ReadOnlyPath.parse(path));
    }

    private WebDavPath(WebDavFileSystem fs, List<String> segments) {
        super(fs, List.copyOf(segments), "WebDavPath");
        this.fs = fs;
    }

    /** The normalized, absolute, slash-separated WebDAV resource path. */
    String path() {
        return normalizedPath();
    }

    @Override
    public WebDavFileSystem getFileSystem() {
        return fs;
    }

    @Override
    protected Path createPath(List<String> segments) {
        return new WebDavPath(fs, segments);
    }

    @Override
    public URI toUri() {
        try {
            return new URI(
                    fs.declaredScheme(), null, fs.host(), fs.port(), path(),
                    null, null);
        } catch (URISyntaxException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }
}
