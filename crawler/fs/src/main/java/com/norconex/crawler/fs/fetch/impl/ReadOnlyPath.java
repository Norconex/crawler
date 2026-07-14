/* Copyright 2026 Norconex Inc.
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
package com.norconex.crawler.fs.fetch.impl;

import java.io.File;
import java.nio.file.FileSystem;
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
 * Shared absolute-path behavior for remote, read-only file systems.
 */
public abstract class ReadOnlyPath implements Path {

    private final FileSystem fileSystem;
    private final List<String> segments;
    private final String displayName;

    protected ReadOnlyPath(
            FileSystem fileSystem, List<String> segments, String displayName) {
        this.fileSystem = fileSystem;
        this.segments = segments;
        this.displayName = displayName;
    }

    protected static List<String> parse(String path) {
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

    protected final FileSystem rawFileSystem() {
        return fileSystem;
    }

    protected final List<String> segments() {
        return segments;
    }

    protected final String normalizedPath() {
        return "/" + String.join("/", segments);
    }

    protected abstract Path createPath(List<String> segments);

    @Override
    public FileSystem getFileSystem() {
        return fileSystem;
    }

    @Override
    public boolean isAbsolute() {
        return true;
    }

    @Override
    public Path getRoot() {
        return createPath(List.of());
    }

    @Override
    public Path getFileName() {
        if (segments.isEmpty()) {
            return null;
        }
        return createPath(List.of(segments.get(segments.size() - 1)));
    }

    @Override
    public Path getParent() {
        if (segments.isEmpty()) {
            return null;
        }
        return createPath(segments.subList(0, segments.size() - 1));
    }

    @Override
    public int getNameCount() {
        return segments.size();
    }

    @Override
    public Path getName(int index) {
        return createPath(List.of(segments.get(index)));
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        return createPath(
                new ArrayList<>(segments.subList(beginIndex, endIndex)));
    }

    @Override
    public boolean startsWith(Path other) {
        return other instanceof ReadOnlyPath o
                && o.rawFileSystem() == rawFileSystem()
                && segments.size() >= o.segments.size()
                && segments.subList(0, o.segments.size()).equals(o.segments);
    }

    @Override
    public boolean startsWith(String other) {
        return startsWith(fileSystem.getPath(other));
    }

    @Override
    public boolean endsWith(Path other) {
        return other instanceof ReadOnlyPath o
                && o.rawFileSystem() == rawFileSystem()
                && segments.size() >= o.segments.size()
                && segments.subList(
                        segments.size() - o.segments.size(),
                        segments.size()).equals(o.segments);
    }

    @Override
    public boolean endsWith(String other) {
        return endsWith(fileSystem.getPath(other));
    }

    @Override
    public Path normalize() {
        return this;
    }

    @Override
    public Path resolve(Path other) {
        if (!(other instanceof ReadOnlyPath o)) {
            throw new IllegalArgumentException(
                    "Not a " + displayName + ": " + other);
        }
        if (o.isAbsolute()) {
            return o;
        }
        var combined = new ArrayList<>(segments);
        combined.addAll(o.segments);
        return createPath(combined);
    }

    @Override
    public Path resolve(String other) {
        return resolve(fileSystem.getPath(other));
    }

    @Override
    public Path resolveSibling(Path other) {
        var parent = getParent();
        return parent == null ? other : parent.resolve(other);
    }

    @Override
    public Path resolveSibling(String other) {
        return resolveSibling(fileSystem.getPath(other));
    }

    @Override
    public Path relativize(Path other) {
        if (!(other instanceof ReadOnlyPath o)
                || o.rawFileSystem() != rawFileSystem()) {
            throw new IllegalArgumentException(
                    "Not a " + displayName + ": " + other);
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
        return createPath(result);
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
                displayName + " paths have no local file representation.");
    }

    @Override
    public WatchKey register(
            WatchService watcher, Kind<?>[] events, Modifier... modifiers) {
        throw new UnsupportedOperationException(
                displayName + " paths do not support watching.");
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
        return normalizedPath()
                .compareTo(((ReadOnlyPath) other).normalizedPath());
    }

    @Override
    public int hashCode() {
        return Objects.hash(rawFileSystem(), segments);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ReadOnlyPath o
                && rawFileSystem() == o.rawFileSystem()
                && segments.equals(o.segments);
    }

    @Override
    public String toString() {
        return normalizedPath();
    }
}
