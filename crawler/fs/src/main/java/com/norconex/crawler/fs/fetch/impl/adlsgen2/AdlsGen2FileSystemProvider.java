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
package com.norconex.crawler.fs.fetch.impl.adlsgen2;

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

import com.azure.core.http.rest.PagedIterable;
import com.azure.storage.file.datalake.DataLakeFileSystemClient;
import com.azure.storage.file.datalake.models.DataLakeFileOpenInputStreamResult;
import com.azure.storage.file.datalake.models.DataLakeStorageException;
import com.azure.storage.file.datalake.models.ListPathsOptions;
import com.azure.storage.file.datalake.models.PathAccessControlEntry;
import com.azure.storage.file.datalake.models.PathItem;
import com.azure.storage.file.datalake.models.PathProperties;
import com.norconex.commons.lang.map.Properties;
import com.norconex.crawler.fs.doc.FsDocMetadata;

import lombok.extern.slf4j.Slf4j;

/**
 * A read-only NIO.2 {@link FileSystemProvider} for ADLS Gen2.
 */
@Slf4j
final class AdlsGen2FileSystemProvider extends FileSystemProvider {

    private static final int HTTP_NOT_FOUND = 404;

    private final Map<String, AdlsGen2FileSystem> fileSystems =
            new ConcurrentHashMap<>();
    private volatile boolean aclDisabled;

    void setAclDisabled(boolean aclDisabled) {
        this.aclDisabled = aclDisabled;
    }

    @Override
    public String getScheme() {
        return "abfss";
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
        throw new UnsupportedOperationException(
                "Use getOrCreateFileSystem(AdlsGen2Location, Function) instead.");
    }

    AdlsGen2FileSystem getOrCreateFileSystem(
            AdlsGen2Location location,
            Function<AdlsGen2Location,
                    DataLakeFileSystemClient> clientFactory) {
        return fileSystems.computeIfAbsent(location.key(), key -> {
            LOG.info(
                    "Opening ADLS Gen2 file system: account={}, filesystem={}",
                    location.account(), location.fileSystem());
            return new AdlsGen2FileSystem(
                    this, location, clientFactory.apply(location));
        });
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        var location = AdlsGen2Location.from(uri);
        var fs = fileSystems.get(location.key());
        if (fs == null) {
            throw new FileSystemNotFoundException(uri.toString());
        }
        return fs;
    }

    @Override
    public AdlsGen2Path getPath(URI uri) {
        var location = AdlsGen2Location.from(uri);
        var fs = (AdlsGen2FileSystem) getFileSystem(uri);
        return fs.getPath(location.path());
    }

    void closeFileSystem(String key) {
        fileSystems.remove(key);
    }

    Collection<AdlsGen2FileSystem> openFileSystems() {
        return List.copyOf(fileSystems.values());
    }

    @Override
    public InputStream newInputStream(Path path, OpenOption... options)
            throws IOException {
        var adlsPath = (AdlsGen2Path) path;
        try {
            DataLakeFileOpenInputStreamResult result = adlsPath.getFileSystem()
                    .client()
                    .getFileClient(adlsPath.pathName())
                    .openInputStream();
            return result.getInputStream();
        } catch (DataLakeStorageException e) {
            if (e.getStatusCode() == HTTP_NOT_FOUND) {
                throw new NoSuchFileException(adlsPath.path());
            }
            throw new IOException(
                    "Could not read ADLS Gen2 file: " + adlsPath.path(), e);
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
        var adlsPath = (AdlsGen2Path) dir;
        var fs = adlsPath.getFileSystem();

        List<Path> children = new ArrayList<>();
        try {
            var options = new ListPathsOptions()
                    .setPath(StringUtils.defaultIfBlank(adlsPath.pathName(),
                            null))
                    .setRecursive(false);
            PagedIterable<PathItem> iterable = fs.client().listPaths(options,
                    null);
            for (PathItem item : iterable) {
                var childPath = fs.getPath("/" + item.getName());
                fs.attrsCache().put(
                        childPath.path(),
                        new AdlsGen2FileAttributes(
                                item.isDirectory(),
                                item.isDirectory() ? 0
                                        : item.getContentLength(),
                                toFileTime(item.getLastModified())));
                if (filter == null || filter.accept(childPath)) {
                    children.add(childPath);
                }
            }
        } catch (DataLakeStorageException e) {
            if (e.getStatusCode() == HTTP_NOT_FOUND) {
                throw new NoSuchFileException(adlsPath.path());
            }
            throw new IOException(
                    "Could not list ADLS Gen2 paths under: " + adlsPath.path(),
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
                && type != AdlsGen2FileAttributes.class) {
            throw new UnsupportedOperationException(type.getName());
        }

        var adlsPath = (AdlsGen2Path) path;
        var fs = adlsPath.getFileSystem();
        if (adlsPath.pathName().isEmpty()) {
            return (A) new AdlsGen2FileAttributes(true, 0, null);
        }

        var cached = fs.attrsCache().get(adlsPath.path());
        if (cached != null) {
            cacheAclIfEnabled(fs, adlsPath);
            return (A) cached;
        }

        var fileClient = fs.client().getFileClient(adlsPath.pathName());
        try {
            if (fileClient.exists()) {
                var props = fileClient.getProperties();
                var attrs = new AdlsGen2FileAttributes(
                        false,
                        props.getFileSize(),
                        toFileTime(props.getLastModified()));
                fs.attrsCache().put(adlsPath.path(), attrs);
                cacheAclIfEnabled(fs, adlsPath);
                return (A) attrs;
            }

            var dirClient = fs.client().getDirectoryClient(adlsPath.pathName());
            if (dirClient.exists()) {
                PathProperties props = dirClient.getProperties();
                var attrs = new AdlsGen2FileAttributes(
                        true,
                        0,
                        toFileTime(props.getLastModified()));
                fs.attrsCache().put(adlsPath.path(), attrs);
                cacheAclIfEnabled(fs, adlsPath);
                return (A) attrs;
            }
        } catch (DataLakeStorageException e) {
            if (e.getStatusCode() != HTTP_NOT_FOUND) {
                throw new IOException(
                        "Could not read ADLS Gen2 path attributes: "
                                + adlsPath.path(),
                        e);
            }
        }
        throw new NoSuchFileException(adlsPath.path());
    }

    private static FileTime toFileTime(OffsetDateTime time) {
        return time == null ? null : FileTime.from(time.toInstant());
    }

    Properties consumeAcl(String path) {
        for (var fs : fileSystems.values()) {
            var props = fs.aclCache().remove(path);
            if (props != null) {
                return props;
            }
        }
        return null;
    }

    private void cacheAclIfEnabled(AdlsGen2FileSystem fs, AdlsGen2Path path)
            throws IOException {
        if (aclDisabled || path.pathName().isEmpty()) {
            return;
        }
        var aclProps = new Properties();
        try {
            PathProperties props;
            var fileClient = fs.client().getFileClient(path.pathName());
            if (fileClient.exists()) {
                props = fileClient.getProperties();
            } else {
                var dirClient = fs.client().getDirectoryClient(path.pathName());
                if (!dirClient.exists()) {
                    return;
                }
                props = dirClient.getProperties();
            }
            if (StringUtils.isNotBlank(props.getOwner())) {
                aclProps.set(FsDocMetadata.ACL + ".owner", props.getOwner());
            }
            if (StringUtils.isNotBlank(props.getGroup())) {
                aclProps.set(FsDocMetadata.ACL + ".group", props.getGroup());
            }
            if (StringUtils.isNotBlank(props.getPermissions())) {
                aclProps.set(FsDocMetadata.ACL + ".permissions",
                        props.getPermissions());
            }
            if (props.getAccessControlList() != null) {
                for (PathAccessControlEntry ace : props
                        .getAccessControlList()) {
                    var scope = StringUtils.defaultIfBlank(
                            ace.isInDefaultScope() ? "DEFAULT" : null,
                            "ACCESS");
                    var type = StringUtils.defaultIfBlank(
                            ace.getAccessControlType() == null
                                    ? null
                                    : ace.getAccessControlType().toString(),
                            "NOTYPE");
                    var principal = StringUtils.defaultIfBlank(
                            ace.getEntityId(), "unknown");
                    var perms = ace.getPermissions() == null
                            ? ""
                            : ace.getPermissions().toString();
                    aclProps.add(
                            FsDocMetadata.ACL + "." + scope + "." + type
                                    + "." + perms,
                            principal);
                }
            }
            if (!aclProps.isEmpty()) {
                fs.aclCache().put(path.path(), aclProps);
            }
        } catch (DataLakeStorageException e) {
            if (e.getStatusCode() != HTTP_NOT_FOUND) {
                throw new IOException(
                        "Could not read ADLS Gen2 ACL data: " + path.path(),
                        e);
            }
        }
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
                    return AdlsGen2FileSystemProvider.this.readAttributes(
                            path, BasicFileAttributes.class);
                }

                @Override
                public void setTimes(
                        FileTime lastModifiedTime, FileTime lastAccessTime,
                        FileTime createTime) {
                    throw new UnsupportedOperationException(
                            "ADLS Gen2 file systems are read-only.");
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
                "ADLS Gen2 file systems do not expose file stores.");
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) {
        throw new UnsupportedOperationException(
                "ADLS Gen2 file systems are read-only.");
    }

    @Override
    public void delete(Path path) {
        throw new UnsupportedOperationException(
                "ADLS Gen2 file systems are read-only.");
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) {
        throw new UnsupportedOperationException(
                "ADLS Gen2 file systems are read-only.");
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) {
        throw new UnsupportedOperationException(
                "ADLS Gen2 file systems are read-only.");
    }

    @Override
    public void setAttribute(
            Path path, String attribute, Object value,
            LinkOption... options) {
        throw new UnsupportedOperationException(
                "ADLS Gen2 file systems are read-only.");
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
