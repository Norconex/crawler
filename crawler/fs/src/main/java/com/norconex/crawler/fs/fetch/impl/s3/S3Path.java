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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchEvent.Modifier;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * A path within an {@link S3FileSystem} (a bucket). Always absolute (all
 * S3 object keys are rooted at {@code /}, the bucket itself); relative-path
 * support is limited to what {@link #resolve(Path)} needs to build a child
 * path from a path segment.
 */
final class S3Path implements Path {

    private final S3FileSystem fs;
    private final List<String> segments;

    S3Path(S3FileSystem fs, String path) {
        this(fs, parse(path));
    }

    private S3Path(S3FileSystem fs, List<String> segments) {
        this.fs = fs;
        this.segments = segments;
    }

    private static List<String> parse(String path) {
        List<String> result = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!result.isEmpty()) {
                    result.remove(result.size() - 1);
                }
            } else {
                result.add(segment);
            }
        }
        return result;
    }

    /** The normalized, absolute, slash-separated path. */
    String path() {
        return "/" + String.join("/", segments);
    }

    /**
     * The S3 object key for this path (no leading slash; empty string for
     * the bucket root).
     */
    String key() {
        return String.join("/", segments);
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
    public boolean isAbsolute() {
        return true;
    }

    @Override
    public Path getRoot() {
        return new S3Path(fs, List.of());
    }

    @Override
    public Path getFileName() {
        if (segments.isEmpty()) {
            return null;
        }
        return new S3Path(fs, List.of(segments.get(segments.size() - 1)));
    }

    @Override
    public Path getParent() {
        if (segments.isEmpty()) {
            return null;
        }
        return new S3Path(fs, segments.subList(0, segments.size() - 1));
    }

    @Override
    public int getNameCount() {
        return segments.size();
    }

    @Override
    public Path getName(int index) {
        return new S3Path(fs, List.of(segments.get(index)));
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        return new S3Path(
                fs, new ArrayList<>(segments.subList(beginIndex, endIndex)));
    }

    @Override
    public boolean startsWith(Path other) {
        return other instanceof S3Path o && o.fs == fs
                && segments.size() >= o.segments.size()
                && segments.subList(0, o.segments.size()).equals(o.segments);
    }

    @Override
    public boolean startsWith(String other) {
        return startsWith(fs.getPath(other));
    }

    @Override
    public boolean endsWith(Path other) {
        return other instanceof S3Path o && o.fs == fs
                && segments.size() >= o.segments.size()
                && segments.subList(
                        segments.size() - o.segments.size(), segments.size())
                        .equals(o.segments);
    }

    @Override
    public boolean endsWith(String other) {
        return endsWith(fs.getPath(other));
    }

    @Override
    public Path normalize() {
        return this;
    }

    @Override
    public Path resolve(Path other) {
        if (!(other instanceof S3Path o)) {
            throw new IllegalArgumentException("Not a S3Path: " + other);
        }
        if (o.isAbsolute()) {
            return o;
        }
        var combined = new ArrayList<>(segments);
        combined.addAll(o.segments);
        return new S3Path(fs, combined);
    }

    @Override
    public Path resolve(String other) {
        return resolve(fs.getPath(other));
    }

    @Override
    public Path resolveSibling(Path other) {
        var parent = getParent();
        return parent == null ? other : parent.resolve(other);
    }

    @Override
    public Path resolveSibling(String other) {
        return resolveSibling(fs.getPath(other));
    }

    @Override
    public Path relativize(Path other) {
        if (!(other instanceof S3Path o) || o.fs != fs) {
            throw new IllegalArgumentException("Not a S3Path: " + other);
        }
        var common = 0;
        while (common < segments.size() && common < o.segments.size()
                && segments.get(common).equals(o.segments.get(common))) {
            common++;
        }
        var result = new ArrayList<String>();
        for (var i = common; i < segments.size(); i++) {
            result.add("..");
        }
        result.addAll(o.segments.subList(common, o.segments.size()));
        return new S3Path(fs, result);
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

    @Override
    public Path toAbsolutePath() {
        return this;
    }

    @Override
    public Path toRealPath(LinkOption... options) {
        return this;
    }

    @Override
    public File toFile() {
        throw new UnsupportedOperationException(
                "S3 paths have no local file representation.");
    }

    @Override
    public WatchKey register(
            WatchService watcher, Kind<?>[] events, Modifier... modifiers) {
        throw new UnsupportedOperationException(
                "S3 paths do not support watching.");
    }

    @Override
    public Iterator<Path> iterator() {
        List<Path> names = new ArrayList<>();
        for (var i = 0; i < segments.size(); i++) {
            names.add(getName(i));
        }
        return names.iterator();
    }

    @Override
    public int compareTo(Path other) {
        return path().compareTo(((S3Path) other).path());
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof S3Path o
                && fs == o.fs
                && segments.equals(o.segments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fs, segments);
    }

    @Override
    public String toString() {
        return path();
    }
}
