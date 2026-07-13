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
package com.norconex.crawler.fs.fetch.impl.hdfs;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.security.auth.Subject;

import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.routing.RoutingSupport;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * A read-only NIO.2 {@link FileSystemProvider} for HDFS, backed by the
 * WebHDFS REST API over Apache HttpClient (no Hadoop client dependency).
 * Each {@link HdfsFileSystem} represents one authority (host + port, the
 * NameNode's WebHDFS/HTTP port - typically 9870, not the RPC port 8020
 * used by the native client).
 */
@Slf4j
final class HdfsFileSystemProvider extends FileSystemProvider {

    private static final int DEFAULT_PORT = 9870;
    private static final String WEBHDFS_PREFIX = "/webhdfs/v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, HdfsFileSystem> fileSystems =
            new ConcurrentHashMap<>();

    @Override
    public String getScheme() {
        return "webhdfs";
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
        throw new UnsupportedOperationException(
                "Use getOrCreateFileSystem(...) instead.");
    }

    /**
     * Atomically gets the already-open file system for this authority, or
     * creates and registers one if none exists yet. Safe to call
     * concurrently for the same authority. {@code clientFactory} receives
     * the resolved target host so it can scope authentication (e.g. an
     * SPNEGO {@code AuthScope}) precisely to it.
     */
    HdfsFileSystem getOrCreateFileSystem(
            URI uri, String username, Subject kerberosSubject,
            Function<HttpHost, CloseableHttpClient> clientFactory) {
        return fileSystems.computeIfAbsent(key(uri), k -> {
            LOG.info("Opening WebHDFS file system: {}", k);
            var host = new HttpHost("http", uri.getHost(), resolvePort(uri));
            return new HdfsFileSystem(
                    this, k, uri.getHost(), resolvePort(uri), username,
                    kerberosSubject, clientFactory.apply(host));
        });
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        var fs = fileSystems.get(key(uri));
        if (fs == null) {
            throw new FileSystemNotFoundException(uri.toString());
        }
        return fs;
    }

    @Override
    public HdfsPath getPath(URI uri) {
        var fs = (HdfsFileSystem) getFileSystem(uri);
        return fs.getPath(StringUtils.defaultIfBlank(uri.getPath(), "/"));
    }

    void closeFileSystem(String key) {
        fileSystems.remove(key);
    }

    Collection<HdfsFileSystem> openFileSystems() {
        return List.copyOf(fileSystems.values());
    }

    private static int resolvePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : DEFAULT_PORT;
    }

    private static String key(URI uri) {
        return uri.getHost() + ":" + resolvePort(uri);
    }

    // --- WebHDFS request building ------------------------------------------

    private static String url(HdfsFileSystem fs, String path, String op) {
        try {
            var query = "op=" + op;
            if (StringUtils.isNotBlank(fs.username())) {
                query += "&user.name="
                        + URLEncoder.encode(fs.username(), UTF_8);
            }
            return new URI(
                    "http", null, fs.host(), fs.port(),
                    WEBHDFS_PREFIX + path, query, null).toString();
        } catch (URISyntaxException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    // Wraps HTTP execution under the Kerberos Subject (SPNEGO), when
    // configured; otherwise runs directly.
    private static <T> T doAs(HdfsFileSystem fs, Callable<T> action)
            throws IOException {
        if (fs.kerberosSubject() == null) {
            try {
                return action.call();
            } catch (IOException | RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
        try {
            return Subject.callAs(fs.kerberosSubject(), action);
        } catch (CompletionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException(
                    "Kerberos-authenticated request failed.", e.getCause());
        }
    }

    private static ClassicHttpResponse execute(
            HdfsFileSystem fs, HttpGet request) throws IOException {
        try {
            return fs.httpClient().execute(
                    RoutingSupport.determineHost(request), request);
        } catch (HttpException e) {
            throw new IOException(
                    "Could not determine target host for "
                            + request.getRequestUri(),
                    e);
        }
    }

    // --- Reads --------------------------------------------------------------

    @Override
    public InputStream newInputStream(Path path, OpenOption... options)
            throws IOException {
        var hdfsPath = (HdfsPath) path;
        var fs = hdfsPath.getFileSystem();
        return doAs(fs, () -> {
            var get = new HttpGet(url(fs, hdfsPath.path(), "OPEN"));
            var resp = execute(fs, get);
            var status = resp.getCode();
            if (status == HttpStatus.SC_NOT_FOUND) {
                resp.close();
                throw new NoSuchFileException(hdfsPath.path());
            }
            if (status != HttpStatus.SC_OK) {
                resp.close();
                throw new IOException(
                        "Unexpected WebHDFS response " + status
                                + " for OPEN " + hdfsPath.path());
            }
            var entity = resp.getEntity();
            if (entity == null) {
                resp.close();
                return InputStream.nullInputStream();
            }
            // Closing this stream releases the underlying connection back
            // to the pool; deliberately not closing `resp` here.
            return entity.getContent();
        });
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
        var hdfsPath = (HdfsPath) dir;
        var fs = hdfsPath.getFileSystem();
        List<Path> children = doAs(fs, () -> {
            var get = new HttpGet(url(fs, hdfsPath.path(), "LISTSTATUS"));
            try (var resp = execute(fs, get)) {
                var status = resp.getCode();
                if (status == HttpStatus.SC_NOT_FOUND) {
                    throw new NoSuchFileException(hdfsPath.path());
                }
                if (status != HttpStatus.SC_OK) {
                    throw new IOException(
                            "Unexpected WebHDFS response " + status
                                    + " for LISTSTATUS " + hdfsPath.path());
                }
                var root = MAPPER.readTree(resp.getEntity().getContent());
                var statuses = root.path("FileStatuses").path("FileStatus");
                List<Path> result = new ArrayList<>();
                for (JsonNode node : statuses) {
                    var name = node.path("pathSuffix").asString("");
                    if (StringUtils.isBlank(name)) {
                        continue;
                    }
                    var childPath = fs.getPath(hdfsPath.path() + "/" + name);
                    fs.attrsCache().put(childPath.path(), toAttributes(node));
                    if (filter == null || filter.accept(childPath)) {
                        result.add(childPath);
                    }
                }
                return result;
            }
        });

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
                && type != HdfsFileAttributes.class) {
            throw new UnsupportedOperationException(type.getName());
        }
        var hdfsPath = (HdfsPath) path;
        var fs = hdfsPath.getFileSystem();

        var cached = fs.attrsCache().get(hdfsPath.path());
        if (cached != null) {
            return (A) cached;
        }

        var attrs = doAs(fs, () -> {
            var get = new HttpGet(
                    url(fs, hdfsPath.path(), "GETFILESTATUS"));
            try (var resp = execute(fs, get)) {
                var status = resp.getCode();
                if (status == HttpStatus.SC_NOT_FOUND) {
                    throw new NoSuchFileException(hdfsPath.path());
                }
                if (status != HttpStatus.SC_OK) {
                    throw new IOException(
                            "Unexpected WebHDFS response " + status
                                    + " for GETFILESTATUS "
                                    + hdfsPath.path());
                }
                var root = MAPPER.readTree(resp.getEntity().getContent());
                return toAttributes(root.path("FileStatus"));
            }
        });
        fs.attrsCache().put(hdfsPath.path(), attrs);
        return (A) attrs;
    }

    private static HdfsFileAttributes toAttributes(JsonNode node) {
        var directory = "DIRECTORY".equals(node.path("type").asString(""));
        var length = node.path("length").asLong(0);
        var modTime = node.path("modificationTime").asLong(0);
        var accessTime = node.path("accessTime").asLong(0);
        return new HdfsFileAttributes(directory, length, modTime, accessTime);
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
                    return HdfsFileSystemProvider.this.readAttributes(
                            path, BasicFileAttributes.class);
                }

                @Override
                public void setTimes(
                        FileTime lastModifiedTime, FileTime lastAccessTime,
                        FileTime createTime) {
                    throw new UnsupportedOperationException(
                            "HDFS file systems are read-only.");
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
                "HDFS file systems do not expose file stores.");
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) {
        throw new UnsupportedOperationException(
                "HDFS file systems are read-only.");
    }

    @Override
    public void delete(Path path) {
        throw new UnsupportedOperationException(
                "HDFS file systems are read-only.");
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) {
        throw new UnsupportedOperationException(
                "HDFS file systems are read-only.");
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) {
        throw new UnsupportedOperationException(
                "HDFS file systems are read-only.");
    }

    @Override
    public void setAttribute(
            Path path, String attribute, Object value,
            LinkOption... options) {
        throw new UnsupportedOperationException(
                "HDFS file systems are read-only.");
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
