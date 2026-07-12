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
package com.norconex.crawler.fs.fetch.impl.azureblob;

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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobItemProperties;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;

import lombok.extern.slf4j.Slf4j;

/**
 * A read-only NIO.2 {@link FileSystemProvider} for Azure Blob Storage.
 */
@Slf4j
final class AzureBlobFileSystemProvider extends FileSystemProvider {

    private static final int HTTP_NOT_FOUND = 404;

    private final Map<String, AzureBlobFileSystem> fileSystems =
            new ConcurrentHashMap<>();

    @Override
    public String getScheme() {
        return "azblob";
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
        throw new UnsupportedOperationException(
                "Use getOrCreateFileSystem(AzureBlobLocation, Function) instead.");
    }

    AzureBlobFileSystem getOrCreateFileSystem(
            AzureBlobLocation location,
            Function<AzureBlobLocation, BlobContainerClient> clientFactory) {
        return fileSystems.computeIfAbsent(location.key(), key -> {
            LOG.info(
                    "Opening Azure Blob file system: account={}, container={}",
                    location.account(),
                    location.container());
            return new AzureBlobFileSystem(
                    this, location, clientFactory.apply(location));
        });
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        var location = AzureBlobLocation.from(uri);
        var fs = fileSystems.get(location.key());
        if (fs == null) {
            throw new FileSystemNotFoundException(uri.toString());
        }
        return fs;
    }

    @Override
    public AzureBlobPath getPath(URI uri) {
        var location = AzureBlobLocation.from(uri);
        var fs = (AzureBlobFileSystem) getFileSystem(uri);
        return fs.getPath(location.path());
    }

    void closeFileSystem(String key) {
        fileSystems.remove(key);
    }

    Collection<AzureBlobFileSystem> openFileSystems() {
        return List.copyOf(fileSystems.values());
    }

    @Override
    public InputStream newInputStream(Path path, OpenOption... options)
            throws IOException {
        var blobPath = (AzureBlobPath) path;
        try {
            return blobPath.getFileSystem()
                    .client()
                    .getBlobClient(blobPath.blobName())
                    .openInputStream();
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == HTTP_NOT_FOUND) {
                throw new NoSuchFileException(blobPath.path());
            }
            throw new IOException(
                    "Could not read Azure blob: " + blobPath.path(), e);
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
        var blobPath = (AzureBlobPath) dir;
        var fs = blobPath.getFileSystem();
        var prefix = blobPath.blobPrefix();

        List<Path> children = new ArrayList<>();
        try {
            var options = new ListBlobsOptions().setPrefix(prefix);
            for (BlobItem item : fs.client().listBlobsByHierarchy("/", options,
                    null)) {
                if (item.isPrefix()) {
                    var childPrefix =
                            StringUtils.removeEnd(item.getName(), "/");
                    if (StringUtils.isBlank(childPrefix)) {
                        continue;
                    }
                    var childPath = fs.getPath("/" + childPrefix);
                    fs.attrsCache().put(
                            childPath.path(),
                            new AzureBlobFileAttributes(true, 0, null));
                    if (filter == null || filter.accept(childPath)) {
                        children.add(childPath);
                    }
                    continue;
                }
                if (item.getName().equals(prefix)) {
                    continue;
                }
                var childPath = fs.getPath("/" + item.getName());
                fs.attrsCache().put(
                        childPath.path(),
                        new AzureBlobFileAttributes(
                                false,
                                contentLength(item),
                                toFileTime(lastModified(item))));
                if (filter == null || filter.accept(childPath)) {
                    children.add(childPath);
                }
            }
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == HTTP_NOT_FOUND) {
                throw new NoSuchFileException(blobPath.path());
            }
            throw new IOException(
                    "Could not list Azure blobs under: " + blobPath.path(),
                    e);
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
                && type != AzureBlobFileAttributes.class) {
            throw new UnsupportedOperationException(type.getName());
        }

        var blobPath = (AzureBlobPath) path;
        var fs = blobPath.getFileSystem();
        if (blobPath.blobName().isEmpty()) {
            return (A) new AzureBlobFileAttributes(true, 0, null);
        }

        var cached = fs.attrsCache().get(blobPath.path());
        if (cached != null) {
            return (A) cached;
        }

        AzureBlobFileAttributes attrs;
        try {
            var props = fs.client()
                    .getBlobClient(blobPath.blobName())
                    .getProperties();
            attrs = new AzureBlobFileAttributes(
                    false,
                    props.getBlobSize(),
                    toFileTime(props.getLastModified()));
        } catch (BlobStorageException e) {
            if (e.getStatusCode() != HTTP_NOT_FOUND) {
                throw new IOException(
                        "Could not read Azure blob attributes: "
                                + blobPath.path(),
                        e);
            }
            attrs = checkVirtualDirectory(fs, blobPath);
        }
        fs.attrsCache().put(blobPath.path(), attrs);
        return (A) attrs;
    }

    private AzureBlobFileAttributes checkVirtualDirectory(
            AzureBlobFileSystem fs, AzureBlobPath path) throws IOException {
        try {
            var options = new ListBlobsOptions()
                    .setPrefix(path.blobPrefix())
                    .setMaxResultsPerPage(1);
            var exists = false;
            for (BlobItem ignored : fs.client().listBlobsByHierarchy("/",
                    options, null)) {
                exists = true;
                break;
            }
            if (exists) {
                return new AzureBlobFileAttributes(true, 0, null);
            }
        } catch (BlobStorageException e) {
            throw new IOException(
                    "Could not check Azure virtual directory: " + path.path(),
                    e);
        }
        throw new NoSuchFileException(path.path());
    }

    private static long contentLength(BlobItem item) {
        var properties = item.getProperties();
        if (properties == null || properties.getContentLength() == null) {
            return 0;
        }
        return properties.getContentLength();
    }

    private static OffsetDateTime lastModified(BlobItem item) {
        BlobItemProperties properties = item.getProperties();
        return properties == null ? null : properties.getLastModified();
    }

    private static FileTime toFileTime(OffsetDateTime lastModified) {
        return lastModified == null
                ? null
                : FileTime.from(lastModified.toInstant());
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
                    return AzureBlobFileSystemProvider.this.readAttributes(
                            path, BasicFileAttributes.class);
                }

                @Override
                public void setTimes(
                        FileTime lastModifiedTime, FileTime lastAccessTime,
                        FileTime createTime) {
                    throw new UnsupportedOperationException(
                            "Azure Blob file systems are read-only.");
                }
            });
        }
        return null;
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes)
            throws IOException {
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
                "Azure Blob file systems do not expose file stores.");
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) {
        throw new UnsupportedOperationException(
                "Azure Blob file systems are read-only.");
    }

    @Override
    public void delete(Path path) {
        throw new UnsupportedOperationException(
                "Azure Blob file systems are read-only.");
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) {
        throw new UnsupportedOperationException(
                "Azure Blob file systems are read-only.");
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) {
        throw new UnsupportedOperationException(
                "Azure Blob file systems are read-only.");
    }

    @Override
    public void setAttribute(
            Path path, String attribute, Object value,
            LinkOption... options) {
        throw new UnsupportedOperationException(
                "Azure Blob file systems are read-only.");
    }

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
            var count = Math.min(dst.remaining(), buffer.remaining());
            var slice = buffer.slice();
            slice.limit(count);
            dst.put(slice);
            buffer.position(buffer.position() + count);
            return count;
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
