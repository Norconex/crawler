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
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.spi.FileSystemProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Exception;

import lombok.extern.slf4j.Slf4j;

/**
 * A read-only NIO.2 {@link FileSystemProvider} for S3, backed directly by
 * the standard (non-CRT) AWS SDK v2 {@link S3Client}. Each
 * {@link S3FileSystem} represents one bucket - S3 has no real directories,
 * so "directories" are simulated the same way the AWS console and CLI do:
 * a key prefix ending in {@code /}, listed via {@code ListObjectsV2} with a
 * {@code /} delimiter.
 */
@Slf4j
final class S3FileSystemProvider extends FileSystemProvider {

    private static final int HTTP_NOT_FOUND = 404;

    private final Map<String, S3FileSystem> fileSystems =
            new ConcurrentHashMap<>();

    @Override
    public String getScheme() {
        return "s3";
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
        throw new UnsupportedOperationException(
                "Use getOrCreateFileSystem(URI, Function) instead.");
    }

    /**
     * Atomically gets the already-open file system for this bucket, or
     * creates and registers one if none exists yet. Safe to call
     * concurrently for the same bucket.
     */
    S3FileSystem getOrCreateFileSystem(
            URI uri, Function<String, S3Client> clientFactory) {
        var bucket = uri.getHost();
        return fileSystems.computeIfAbsent(bucket, b -> {
            LOG.info("Opening S3 file system: bucket={}", b);
            return new S3FileSystem(this, b, b, clientFactory.apply(b));
        });
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        var fs = fileSystems.get(uri.getHost());
        if (fs == null) {
            throw new FileSystemNotFoundException(uri.toString());
        }
        return fs;
    }

    @Override
    public S3Path getPath(URI uri) {
        var fs = (S3FileSystem) getFileSystem(uri);
        return fs.getPath(StringUtils.defaultIfBlank(uri.getPath(), "/"));
    }

    void closeFileSystem(String bucket) {
        fileSystems.remove(bucket);
    }

    Collection<S3FileSystem> openFileSystems() {
        return List.copyOf(fileSystems.values());
    }

    // --- Reads --------------------------------------------------------

    @Override
    public InputStream newInputStream(Path path, OpenOption... options)
            throws IOException {
        var s3Path = (S3Path) path;
        var fs = s3Path.getFileSystem();
        try {
            var req = GetObjectRequest.builder()
                    .bucket(fs.bucket())
                    .key(s3Path.key())
                    .build();
            // ResponseInputStream is itself Closeable and releases the
            // underlying HTTP connection when closed by the caller.
            return fs.client().getObject(req);
        } catch (S3Exception e) {
            if (e.statusCode() == HTTP_NOT_FOUND) {
                throw new NoSuchFileException(s3Path.path());
            }
            throw new IOException(
                    "Could not read S3 object: " + s3Path.path(), e);
        }
    }

    @Override
    public SeekableByteChannel newByteChannel(
            Path path, Set<? extends OpenOption> options,
            FileAttribute<?>... attrs) throws IOException {
        byte[] content;
        try (var is = newInputStream(path)) {
            content = is.readAllBytes();
        }
        return new InMemorySeekableByteChannel(content);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(
            Path dir, Filter<? super Path> filter) throws IOException {
        var s3Path = (S3Path) dir;
        var fs = s3Path.getFileSystem();
        var prefix = s3Path.keyAsPrefix();

        List<Path> children = new ArrayList<>();
        try {
            var req = ListObjectsV2Request.builder()
                    .bucket(fs.bucket())
                    .prefix(prefix)
                    .delimiter("/")
                    .build();
            for (var page : fs.client().listObjectsV2Paginator(req)) {
                for (var obj : page.contents()) {
                    if (obj.key().equals(prefix)) {
                        // The zero-byte "directory marker" object some
                        // tools create for this directory itself, not a
                        // child.
                        continue;
                    }
                    var childPath = fs.getPath("/" + obj.key());
                    fs.attrsCache().put(
                            childPath.path(),
                            new S3FileAttributes(
                                    false,
                                    obj.size() == null ? 0 : obj.size(),
                                    toFileTime(obj.lastModified())));
                    if (filter == null || filter.accept(childPath)) {
                        children.add(childPath);
                    }
                }
                for (var cp : page.commonPrefixes()) {
                    var childKey = StringUtils.removeEnd(cp.prefix(), "/");
                    if (StringUtils.isBlank(childKey)) {
                        continue;
                    }
                    var childPath = fs.getPath("/" + childKey);
                    fs.attrsCache().put(
                            childPath.path(),
                            new S3FileAttributes(true, 0, null));
                    if (filter == null || filter.accept(childPath)) {
                        children.add(childPath);
                    }
                }
            }
        } catch (S3Exception e) {
            if (e.statusCode() == HTTP_NOT_FOUND) {
                throw new NoSuchFileException(s3Path.path());
            }
            throw new IOException(
                    "Could not list S3 objects under: " + s3Path.path(), e);
        }

        var iterator = children.iterator();
        return new DirectoryStream<>() {
            @Override
            public java.util.Iterator<Path> iterator() {
                return iterator;
            }

            @Override
            public void close() {
                //NOOP
            }
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends BasicFileAttributes> A readAttributes(
            Path path, Class<A> type, LinkOption... options)
            throws IOException {
        if (type != BasicFileAttributes.class
                && type != S3FileAttributes.class) {
            throw new UnsupportedOperationException(type.getName());
        }
        var s3Path = (S3Path) path;
        var fs = s3Path.getFileSystem();

        // The bucket root is always a directory; never a real S3 object.
        if (s3Path.key().isEmpty()) {
            return (A) new S3FileAttributes(true, 0, null);
        }

        var cached = fs.attrsCache().get(s3Path.path());
        if (cached != null) {
            return (A) cached;
        }

        S3FileAttributes attrs;
        try {
            var req = HeadObjectRequest.builder()
                    .bucket(fs.bucket())
                    .key(s3Path.key())
                    .build();
            var resp = fs.client().headObject(req);
            attrs = new S3FileAttributes(
                    false,
                    resp.contentLength() == null ? 0 : resp.contentLength(),
                    toFileTime(resp.lastModified()));
        } catch (S3Exception e) {
            if (e.statusCode() != HTTP_NOT_FOUND) {
                throw new IOException(
                        "Could not read S3 object attributes: "
                                + s3Path.path(),
                        e);
            }
            // Not a real object; S3 has no real directories, so check
            // whether any object exists under this prefix instead.
            attrs = checkVirtualDirectory(fs, s3Path);
        }
        fs.attrsCache().put(s3Path.path(), attrs);
        return (A) attrs;
    }

    private S3FileAttributes checkVirtualDirectory(
            S3FileSystem fs, S3Path path) throws IOException {
        try {
            var req = ListObjectsV2Request.builder()
                    .bucket(fs.bucket())
                    .prefix(path.keyAsPrefix())
                    .maxKeys(1)
                    .build();
            var resp = fs.client().listObjectsV2(req);
            if (!resp.contents().isEmpty()
                    || !resp.commonPrefixes().isEmpty()) {
                return new S3FileAttributes(true, 0, null);
            }
        } catch (S3Exception e) {
            throw new IOException(
                    "Could not check S3 virtual directory: " + path.path(),
                    e);
        }
        throw new NoSuchFileException(path.path());
    }

    private static FileTime toFileTime(Instant instant) {
        return instant == null ? null : FileTime.from(instant);
    }

    @Override
    public Map<String, Object> readAttributes(
            Path path, String attributes, LinkOption... options) {
        throw new UnsupportedOperationException(
                "Attribute-name based reads are not supported;"
                        + " use readAttributes(Path, Class).");
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(
            Path path, Class<V> type, LinkOption... options) {
        if (type == BasicFileAttributeView.class) {
            return type.cast(new BasicFileAttributeView() {
                @Override
                public String name() {
                    return "basic";
                }

                @Override
                public BasicFileAttributes readAttributes()
                        throws IOException {
                    return S3FileSystemProvider.this.readAttributes(
                            path, BasicFileAttributes.class);
                }

                @Override
                public void setTimes(
                        FileTime lastModifiedTime, FileTime lastAccessTime,
                        FileTime createTime) {
                    throw new UnsupportedOperationException(
                            "S3 file systems are read-only.");
                }
            });
        }
        return null;
    }

    // --- Misc -----------------------------------------------------------

    @Override
    public void checkAccess(Path path, AccessMode... modes)
            throws IOException {
        // Existence check; throws NoSuchFileException if missing.
        readAttributes(path, BasicFileAttributes.class);
        for (var mode : modes) {
            if (mode == AccessMode.WRITE || mode == AccessMode.EXECUTE) {
                throw new AccessDeniedException(path.toString());
            }
        }
    }

    @Override
    public boolean isSameFile(Path path, Path path2) {
        return path.equals(path2);
    }

    @Override
    public boolean isHidden(Path path) {
        return false;
    }

    @Override
    public FileStore getFileStore(Path path) {
        throw new UnsupportedOperationException(
                "S3 file systems do not expose file stores.");
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) {
        throw new UnsupportedOperationException(
                "S3 file systems are read-only.");
    }

    @Override
    public void delete(Path path) {
        throw new UnsupportedOperationException(
                "S3 file systems are read-only.");
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) {
        throw new UnsupportedOperationException(
                "S3 file systems are read-only.");
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) {
        throw new UnsupportedOperationException(
                "S3 file systems are read-only.");
    }

    @Override
    public void setAttribute(
            Path path, String attribute, Object value,
            LinkOption... options) {
        throw new UnsupportedOperationException(
                "S3 file systems are read-only.");
    }

    /** A read-only {@link SeekableByteChannel} over an in-memory buffer. */
    private static final class InMemorySeekableByteChannel
            implements SeekableByteChannel {

        private final ByteBuffer buffer;
        private boolean open = true;

        InMemorySeekableByteChannel(byte[] content) {
            buffer = ByteBuffer.wrap(content);
        }

        @Override
        public int read(ByteBuffer dst) {
            if (!buffer.hasRemaining()) {
                return -1;
            }
            var n = Math.min(dst.remaining(), buffer.remaining());
            var slice = buffer.slice();
            slice.limit(n);
            dst.put(slice);
            buffer.position(buffer.position() + n);
            return n;
        }

        @Override
        public int write(ByteBuffer src) {
            throw new NonWritableChannelException();
        }

        @Override
        public long position() {
            return buffer.position();
        }

        @Override
        public SeekableByteChannel position(long newPosition) {
            buffer.position((int) newPosition);
            return this;
        }

        @Override
        public long size() {
            return buffer.limit();
        }

        @Override
        public SeekableByteChannel truncate(long size) {
            throw new NonWritableChannelException();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
