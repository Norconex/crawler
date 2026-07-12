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
 * A path within a {@link SmbFileSystem}. Always absolute (all SMB resource
 * paths are rooted at {@code /}, with the share name as the first
 * segment); relative-path support is limited to what {@link #resolve(Path)}
 * needs to build a child path from a path segment.
 */
final class SmbPath implements Path {

    private final SmbFileSystem fs;
    private final List<String> segments;

    SmbPath(SmbFileSystem fs, String path) {
        this(fs, parse(path));
    }

    private SmbPath(SmbFileSystem fs, List<String> segments) {
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

    /** The normalized, absolute, slash-separated SMB resource path. */
    String path() {
        return "/" + String.join("/", segments);
    }

    @Override
    public SmbFileSystem getFileSystem() {
        return fs;
    }

    @Override
    public boolean isAbsolute() {
        return true;
    }

    @Override
    public Path getRoot() {
        return new SmbPath(fs, List.of());
    }

    @Override
    public Path getFileName() {
        if (segments.isEmpty()) {
            return null;
        }
        return new SmbPath(fs, List.of(segments.get(segments.size() - 1)));
    }

    @Override
    public Path getParent() {
        if (segments.isEmpty()) {
            return null;
        }
        return new SmbPath(fs, segments.subList(0, segments.size() - 1));
    }

    @Override
    public int getNameCount() {
        return segments.size();
    }

    @Override
    public Path getName(int index) {
        return new SmbPath(fs, List.of(segments.get(index)));
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        return new SmbPath(
                fs, new ArrayList<>(segments.subList(beginIndex, endIndex)));
    }

    @Override
    public boolean startsWith(Path other) {
        return other instanceof SmbPath o && o.fs == fs
                && segments.size() >= o.segments.size()
                && segments.subList(0, o.segments.size()).equals(o.segments);
    }

    @Override
    public boolean startsWith(String other) {
        return startsWith(fs.getPath(other));
    }

    @Override
    public boolean endsWith(Path other) {
        return other instanceof SmbPath o && o.fs == fs
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
        if (!(other instanceof SmbPath o)) {
            throw new IllegalArgumentException("Not a SmbPath: " + other);
        }
        if (o.isAbsolute()) {
            return o;
        }
        var combined = new ArrayList<>(segments);
        combined.addAll(o.segments);
        return new SmbPath(fs, combined);
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
        if (!(other instanceof SmbPath o) || o.fs != fs) {
            throw new IllegalArgumentException("Not a SmbPath: " + other);
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
        return new SmbPath(fs, result);
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
                "SMB paths have no local file representation.");
    }

    @Override
    public WatchKey register(
            WatchService watcher, Kind<?>[] events, Modifier... modifiers) {
        throw new UnsupportedOperationException(
                "SMB paths do not support watching.");
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
        return path().compareTo(((SmbPath) other).path());
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof SmbPath o
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
