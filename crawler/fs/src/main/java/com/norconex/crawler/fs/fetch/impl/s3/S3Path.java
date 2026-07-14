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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import com.norconex.crawler.fs.fetch.impl.ReadOnlyPath;

/**
 * A path within an {@link S3FileSystem} (a bucket). Always absolute (all
 * S3 object keys are rooted at {@code /}, the bucket itself); relative-path
 * support is limited to what {@link #resolve(Path)} needs to build a child
 * path from a path segment.
 */
final class S3Path extends ReadOnlyPath {

    private final S3FileSystem fs;

    S3Path(S3FileSystem fs, String path) {
        this(fs, ReadOnlyPath.parse(path));
    }

    private S3Path(S3FileSystem fs, List<String> segments) {
        super(fs, List.copyOf(segments), "S3Path");
        this.fs = fs;
    }

    /** The normalized, absolute, slash-separated path. */
    String path() {
        return normalizedPath();
    }

    /**
     * The S3 object key for this path (no leading slash; empty string for
     * the bucket root).
     */
    String key() {
        return String.join("/", segments());
    }

    /** This path's key with a trailing slash, used to list it as a prefix. */
    String keyAsPrefix() {
        var k = key();
        return k.isEmpty() || k.endsWith("/") ? k : k + "/";
    }

    @Override
    public S3FileSystem getFileSystem() {
        return fs;
    }

    @Override
    protected Path createPath(List<String> segments) {
        return new S3Path(fs, segments);
    }

    @Override
    public URI toUri() {
        try {
            return new URI(
                    "s3", null, fs.bucket(), -1, path(), null, null);
        } catch (URISyntaxException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }
}
