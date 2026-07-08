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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpHead;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.routing.RoutingSupport;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.io.entity.StringEntity;

import com.norconex.commons.lang.xml.Xml;

import lombok.extern.slf4j.Slf4j;

/**
 * A read-only NIO.2 {@link FileSystemProvider} for WebDAV repositories,
 * with a plain-HTTP single-resource fallback. Each {@link WebDavFileSystem}
 * represents one authority (declared scheme + host + port); resource paths
 * within it map to WebDAV/HTTP paths.
 *
 * <p>
 * Directory traversal uses the WebDAV {@code PROPFIND} method with
 * {@code Depth: 1}; content retrieval uses {@code GET}. When the declared
 * scheme is plain {@code http}/{@code https} (as opposed to
 * {@code webdav}/{@code webdavs}), no traversal is attempted and each
 * reference is treated as a single fetchable resource, mirroring the prior
 * VFS {@code UrlFileProvider} behaviour.
 * </p>
 */
@Slf4j
final class WebDavFileSystemProvider extends FileSystemProvider {

    // Env keys populated by WebDavFetcher.
    static final String ENV_CLIENT_SUPPLIER = "clientSupplier";

    private static final Set<String> WEBDAV_SCHEMES =
            Set.of("webdav", "webdavs");

    private final Map<String, WebDavFileSystem> fileSystems =
            new ConcurrentHashMap<>();

    @Override
    public String getScheme() {
        return "webdav";
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env)
            throws IOException {
        var key = key(uri);
        if (fileSystems.containsKey(key)) {
            throw new FileSystemAlreadyExistsException(key);
        }
        return getOrCreateFileSystem(uri, env);
    }

    /**
     * Atomically gets the already-open file system for this authority, or
     * creates and registers one if none exists yet. Safe to call
     * concurrently for the same authority.
     */
    WebDavFileSystem getOrCreateFileSystem(URI uri, Map<String, ?> env) {
        return fileSystems.computeIfAbsent(
                key(uri), k -> createFileSystem(uri, env));
    }

    @SuppressWarnings("unchecked")
    private WebDavFileSystem createFileSystem(URI uri, Map<String, ?> env) {
        var declaredScheme = uri.getScheme().toLowerCase();
        var transportScheme = switch (declaredScheme) {
            case "webdav", "http" -> "http";
            case "webdavs", "https" -> "https";
            default -> throw new IllegalArgumentException(
                    "Unsupported WebDAV scheme: " + declaredScheme);
        };
        var supplier =
                (Supplier<CloseableHttpClient>) env.get(ENV_CLIENT_SUPPLIER);
        var client = supplier.get();
        LOG.info("Opening WebDAV file system: {}://{}:{}",
                declaredScheme, uri.getHost(), uri.getPort());
        return new WebDavFileSystem(
                this, key(uri), declaredScheme, transportScheme,
                uri.getHost(), uri.getPort(), client);
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
    public WebDavPath getPath(URI uri) {
        var fs = (WebDavFileSystem) getFileSystem(uri);
        return fs.getPath(StringUtils.defaultIfBlank(uri.getPath(), "/"));
    }

    void closeFileSystem(String key) {
        fileSystems.remove(key);
    }

    Collection<WebDavFileSystem> openFileSystems() {
        return List.copyOf(fileSystems.values());
    }

    // Authority-only key: declared scheme + host + port. Path is ignored so
    // all references to the same server share one file system (and one
    // HTTP connection pool).
    private static String key(URI uri) {
        return uri.getScheme().toLowerCase() + "://" + uri.getHost()
                + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
    }

    private static boolean isWebDav(WebDavFileSystem fs) {
        return WEBDAV_SCHEMES.contains(fs.declaredScheme());
    }

    // --- Transport helpers -----------------------------------------------

    // Builds the absolute transport URL (http/https) for a resource path,
    // properly percent-encoding the path.
    private static String url(WebDavFileSystem fs, String path) {
        try {
            return new URI(
                    fs.transportScheme(), null, fs.host(), fs.port(), path,
                    null, null).toString();
        } catch (URISyntaxException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    // --- Reads -----------------------------------------------------------

    @Override
    public InputStream newInputStream(Path path, OpenOption... options)
            throws IOException {
        var wdPath = (WebDavPath) path;
        var fs = wdPath.getFileSystem();
        var get = new HttpGet(url(fs, wdPath.path()));
        ClassicHttpResponse resp;
        try {
            resp = fs.httpClient().execute(
                    RoutingSupport.determineHost(get), get);
        } catch (HttpException e) {
            throw new IOException(
                    "Could not determine target host for "
                            + url(fs, wdPath.path()),
                    e);
        }
        var status = resp.getCode();
        if (status == HttpStatus.SC_NOT_FOUND) {
            resp.close();
            throw new NoSuchFileException(wdPath.path());
        }
        if (status != HttpStatus.SC_OK) {
            resp.close();
            throw new IOException(
                    "Unexpected HTTP response " + status + " for "
                            + url(fs, wdPath.path()));
        }
        var entity = resp.getEntity();
        if (entity == null) {
            resp.close();
            return InputStream.nullInputStream();
        }
        // Closing this stream releases the underlying connection back to
        // the pool (EofSensorInputStream).
        return entity.getContent();
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
        var wdPath = (WebDavPath) dir;
        var fs = wdPath.getFileSystem();

        List<Path> children = new ArrayList<>();
        if (isWebDav(fs)) {
            var doc = propfind(fs, wdPath.path(), 1);
            var selfPath = normalizePath(wdPath.path());
            for (Xml response : responses(doc)) {
                var childPath = hrefToPath(fs, response);
                if (childPath == null
                        || normalizePath(childPath.path()).equals(selfPath)) {
                    // The collection includes itself in a Depth:1 listing.
                    continue;
                }
                fs.attrsCache().put(childPath.path(), toAttributes(response));
                if (filter == null || filter.accept(childPath)) {
                    children.add(childPath);
                }
            }
        }
        // Plain HTTP resources have no directory concept: empty listing.

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
                && type != WebDavFileAttributes.class) {
            throw new UnsupportedOperationException(type.getName());
        }
        var wdPath = (WebDavPath) path;
        var fs = wdPath.getFileSystem();

        var cached = fs.attrsCache().get(wdPath.path());
        if (cached != null) {
            return (A) cached;
        }

        WebDavFileAttributes attrs;
        if (isWebDav(fs)) {
            var doc = propfind(fs, wdPath.path(), 0);
            var response = responses(doc).stream().findFirst().orElseThrow(
                    () -> new NoSuchFileException(wdPath.path()));
            attrs = toAttributes(response);
        } else {
            attrs = httpHeadAttributes(fs, wdPath.path());
        }
        fs.attrsCache().put(wdPath.path(), attrs);
        return (A) attrs;
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
                    return WebDavFileSystemProvider.this.readAttributes(
                            path, BasicFileAttributes.class);
                }

                @Override
                public void setTimes(
                        FileTime lastModifiedTime, FileTime lastAccessTime,
                        FileTime createTime) {
                    throw new UnsupportedOperationException(
                            "WebDAV file systems are read-only.");
                }
            });
        }
        return null;
    }

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
                "WebDAV file systems do not expose file stores.");
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) {
        throw new UnsupportedOperationException(
                "WebDAV file systems are read-only.");
    }

    @Override
    public void delete(Path path) {
        throw new UnsupportedOperationException(
                "WebDAV file systems are read-only.");
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) {
        throw new UnsupportedOperationException(
                "WebDAV file systems are read-only.");
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) {
        throw new UnsupportedOperationException(
                "WebDAV file systems are read-only.");
    }

    @Override
    public void setAttribute(
            Path path, String attribute, Object value,
            LinkOption... options) {
        throw new UnsupportedOperationException(
                "WebDAV file systems are read-only.");
    }

    // --- WebDAV protocol -------------------------------------------------

    // Standard set of live properties; requesting them explicitly is more
    // predictable across servers than an allprop request.
    private static final String PROPFIND_BODY =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <propfind xmlns="DAV:">
              <prop>
                <resourcetype/>
                <getcontentlength/>
                <getlastmodified/>
                <getcontenttype/>
              </prop>
            </propfind>""";

    private Xml propfind(WebDavFileSystem fs, String path, int depth)
            throws IOException {
        var req = new HttpUriRequestBase(
                "PROPFIND", URI.create(url(fs, path)));
        req.setHeader("Depth", Integer.toString(depth));
        req.setEntity(new StringEntity(
                PROPFIND_BODY, ContentType.create(
                        "application/xml", StandardCharsets.UTF_8)));
        ClassicHttpResponse resp;
        try {
            resp = fs.httpClient().execute(
                    RoutingSupport.determineHost(req), req);
        } catch (HttpException e) {
            throw new IOException(
                    "Could not determine target host for "
                            + url(fs, path),
                    e);
        }
        try (resp) {
            var status = resp.getCode();
            if (status == HttpStatus.SC_NOT_FOUND) {
                throw new NoSuchFileException(path);
            }
            // 207 Multi-Status is the normal PROPFIND success code; some
            // servers answer a plain 200 for a single resource.
            if (status != 207 && status != HttpStatus.SC_OK) {
                var body = resp.getEntity() == null ? ""
                        : IOUtils.toString(
                                resp.getEntity().getContent(),
                                StandardCharsets.UTF_8);
                LOG.debug("PROPFIND {} failed ({}): {}", path, status, body);
                throw new IOException(
                        "Unexpected PROPFIND response " + status
                                + " for " + url(fs, path));
            }
            return new Xml(new InputStreamReader(
                    resp.getEntity().getContent(), StandardCharsets.UTF_8));
        }
    }

    // Namespace-agnostic: the Xml wrapper parses without namespace
    // awareness, so WebDAV's prefixed elements (D:, lp1:, ...) are matched
    // by local name.
    private static List<Xml> responses(Xml multistatus) {
        return multistatus.getXMLList(
                "/*[local-name()='multistatus']"
                        + "/*[local-name()='response']");
    }

    // Extracts the resource path from a <response>'s <href> and builds a
    // WebDavPath. Returns null when no usable href is present.
    private static WebDavPath hrefToPath(WebDavFileSystem fs, Xml response) {
        var href = response.getString("*[local-name()='href']/text()");
        if (StringUtils.isBlank(href)) {
            return null;
        }
        String pathPart;
        try {
            // Handles both absolute URLs and absolute paths; decodes
            // percent-encoding.
            pathPart = new URI(href).getPath();
        } catch (URISyntaxException e) {
            pathPart = href;
        }
        if (StringUtils.isBlank(pathPart)) {
            return null;
        }
        return fs.getPath(pathPart);
    }

    private static WebDavFileAttributes toAttributes(Xml response) {
        // Pick the <prop> from the propstat whose <status> is 200; some
        // servers group not-found props under a separate 404 propstat.
        Xml prop = null;
        for (Xml propstat : response.getXMLList(
                "*[local-name()='propstat']")) {
            var st = propstat.getString("*[local-name()='status']/text()");
            var candidate = propstat.getXML("*[local-name()='prop']");
            if (st != null && st.contains("200")) {
                prop = candidate;
                break;
            }
            if (prop == null) {
                prop = candidate;
            }
        }
        if (prop == null) {
            prop = response.getXML(".//*[local-name()='prop']");
        }

        var directory = prop != null && prop.getXML(
                "*[local-name()='resourcetype']"
                        + "/*[local-name()='collection']") != null;
        var length = prop == null ? -1
                : NumberUtils.toLong(
                        prop.getString(
                                "*[local-name()='getcontentlength']/text()"),
                        -1);
        var lastModified = parseHttpDate(prop == null ? null
                : prop.getString("*[local-name()='getlastmodified']/text()"));
        return new WebDavFileAttributes(directory, length, lastModified);
    }

    // --- Plain HTTP fallback ---------------------------------------------

    private WebDavFileAttributes httpHeadAttributes(
            WebDavFileSystem fs, String path) throws IOException {
        var head = new HttpHead(url(fs, path));
        ClassicHttpResponse resp;
        try {
            resp = fs.httpClient().execute(
                    RoutingSupport.determineHost(head), head);
        } catch (HttpException e) {
            throw new IOException(
                    "Could not determine target host for "
                            + url(fs, path),
                    e);
        }
        try (resp) {
            var status = resp.getCode();
            if (status == HttpStatus.SC_NOT_FOUND) {
                throw new NoSuchFileException(path);
            }
            if (status != HttpStatus.SC_OK) {
                throw new IOException(
                        "Unexpected HTTP response " + status + " for "
                                + url(fs, path));
            }
            var lenHeader = resp.getFirstHeader("Content-Length");
            var length = lenHeader == null ? -1
                    : NumberUtils.toLong(lenHeader.getValue(), -1);
            var modHeader = resp.getFirstHeader("Last-Modified");
            var lastModified = parseHttpDate(
                    modHeader == null ? null : modHeader.getValue());
            // A plain HTTP resource is always treated as a regular file.
            return new WebDavFileAttributes(false, length, lastModified);
        }
    }

    private static FileTime parseHttpDate(String httpDate) {
        if (StringUtils.isBlank(httpDate)) {
            return FileTime.fromMillis(0);
        }
        try {
            return FileTime.fromMillis(ZonedDateTime.parse(
                    httpDate, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli());
        } catch (RuntimeException e) {
            LOG.debug("Could not parse HTTP date: {}", httpDate);
            return FileTime.fromMillis(0);
        }
    }

    private static String normalizePath(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
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
