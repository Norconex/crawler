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
package com.norconex.crawler.fs.fetch.impl.cmis;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
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
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.message.BasicHeader;

import com.norconex.commons.lang.xml.Xml;

import lombok.extern.slf4j.Slf4j;

/**
 * A minimal, read-only NIO.2 {@link FileSystemProvider} for CMIS
 * repositories exposed over the Atom binding. Each {@link CmisFileSystem}
 * represents one repository reached through one Atom service endpoint;
 * object paths within it map to CMIS folder/document paths.
 */
@Slf4j
final class CmisFileSystemProvider extends FileSystemProvider {

    // no paging: CMIS Atom feeds don't page reliably across servers, and
    // the prior VFS-based implementation never paged either.
    private static final int MAX_ITEMS = 1_000_000;
    private static final String REPOSITORY_XPATH =
            "/service/workspace/repositoryInfo"; //NOSONAR

    private final Map<String, CmisFileSystem> fileSystems =
            new ConcurrentHashMap<>();

    @Override
    public String getScheme() {
        return "cmis";
    }

    /**
     * Opens a new file system for the CMIS Atom service endpoint given by
     * {@code uri}'s scheme-specific part (e.g.
     * {@code cmis:http://host:port/atom}). Recognized environment keys:
     * {@code username}, {@code password}, {@code repositoryId}.
     * @throws FileSystemAlreadyExistsException if already open; callers
     *     needing "open or reuse" semantics should use
     *     {@link #getOrCreateFileSystem(URI, Map)} instead, which is
     *     atomic and safe under concurrent callers for the same endpoint
     */
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
     * Atomically gets the already-open file system for this endpoint, or
     * creates and registers one if none exists yet. Unlike
     * {@link #newFileSystem(URI, Map)}, this is safe to call concurrently
     * for the same endpoint (e.g. from multiple crawler threads).
     */
    CmisFileSystem getOrCreateFileSystem(URI uri, Map<String, ?> env)
            throws IOException {
        var key = key(uri);
        try {
            return fileSystems.computeIfAbsent(key, k -> {
                try {
                    return createFileSystem(k, env);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private CmisFileSystem createFileSystem(
            String endpointUrl, Map<String, ?> env) throws IOException {
        var httpBuilder = HttpClientBuilder.create();
        httpBuilder.setDefaultHeaders(
                List.of(new BasicHeader(
                        "Accept", "application/atom+xml;type=feed")));
        var username = (String) env.get("username");
        if (StringUtils.isNotBlank(username)) {
            var credentials = new BasicCredentialsProvider();
            var password = (String) env.get("password");
            credentials.setCredentials(
                    new AuthScope(null, null, -1, null, null),
                    new UsernamePasswordCredentials(
                            username,
                            password == null
                                    ? new char[0]
                                    : password.toCharArray()));
            httpBuilder.setDefaultCredentialsProvider(credentials);
        }

        var session = new CmisAtomSession(httpBuilder.build());
        session.setEndpointURL(endpointUrl);
        LOG.info("CMIS Atom endpoint URL: {}", endpointUrl);
        resolveRepo(session, endpointUrl, (String) env.get("repositoryId"));

        return new CmisFileSystem(this, endpointUrl, session);
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
    public CmisPath getPath(URI uri) {
        var raw = key(uri);
        var bangIdx = raw.indexOf('!');
        var endpointUrl = bangIdx < 0 ? raw : raw.substring(0, bangIdx);
        var innerPath = bangIdx < 0 ? "/" : raw.substring(bangIdx + 1);
        return (CmisPath) getFileSystem(
                URI.create("cmis:" + endpointUrl))
                        .getPath(StringUtils.defaultIfBlank(innerPath, "/"));
    }

    void closeFileSystem(String endpointUrl) {
        fileSystems.remove(endpointUrl);
    }

    Collection<CmisFileSystem> openFileSystems() {
        return List.copyOf(fileSystems.values());
    }

    private static String key(URI uri) {
        return uri.getSchemeSpecificPart();
    }

    private void resolveRepo(
            CmisAtomSession session, String endpointUrl, String repositoryId)
            throws IOException {
        var doc = session.getDocument(endpointUrl);
        Xml repoNode;
        if (StringUtils.isNotBlank(repositoryId)) {
            LOG.info("Using CMIS repository matching id: {}", repositoryId);
            repoNode = doc.getXML(
                    "%s[repositoryId=\"%s\"]"
                            .formatted(REPOSITORY_XPATH, repositoryId));
        } else {
            LOG.info("Using first CMIS repository found.");
            repoNode = doc.getXML(REPOSITORY_XPATH + "[1]");
        }
        if (repoNode == null) {
            throw new IOException(
                    "No CMIS repository found at " + endpointUrl);
        }
        session.setRepoId(repoNode.getString("repositoryId"));
        session.setRepoName(repoNode.getString("repositoryName"));
        session.setObjectByPathTemplate(getTemplateURL(doc, "objectbypath"));
        session.setQueryTemplate(getTemplateURL(doc, "query"));
    }

    private String getTemplateURL(Xml doc, String type) {
        // We always use some of the same defaults, so we can already
        // replace parts of the URL.
        var tmplUrl = doc.getString(
                "/service/workspace/uritemplate[type='%s']/template/text()"
                        .formatted(type));
        Map<String, Object> vars = new HashMap<>();
        vars.put("filter", "");
        vars.put("includeAllowableActions", true);
        vars.put("includeACL", true);
        vars.put("includePolicyIds", true);
        vars.put("includeRelationships", "none");
        vars.put("renditionFilter", "cmis%3Anone");
        return org.apache.commons.text.StringSubstitutor.replace(
                tmplUrl, vars, "{", "}");
    }

    // --- Reads -----------------------------------------------------

    @Override
    public InputStream newInputStream(Path path, OpenOption... options)
            throws IOException {
        var cmisPath = (CmisPath) path;
        var entry = cmisPath.getFileSystem().entry(cmisPath.path());
        var attrs = new CmisFileAttributes(entry);
        var contentUrl = entry.getString("/entry/content/@src");
        if (StringUtils.isBlank(contentUrl) || attrs.size() <= 0) {
            return InputStream.nullInputStream();
        }
        return cmisPath.getFileSystem().session().getStream(contentUrl);
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
        var cmisPath = (CmisPath) dir;
        var fs = cmisPath.getFileSystem();
        var entry = fs.entry(cmisPath.path());
        var childrenUrl = entry.getString(
                "/entry/link[@rel='down' and "
                        + "@type='application/atom+xml;type=feed']/@href");

        List<Path> children = new ArrayList<>();
        if (StringUtils.isNotBlank(childrenUrl)) {
            childrenUrl += (childrenUrl.contains("?") ? "&" : "?")
                    + "includeAllowableActions=false"
                    + "&includeRelationships=none"
                    + "&renditionFilter=cmis%3Anone&includePathSegment=true"
                    + "&maxItems=" + MAX_ITEMS + "&skipCount=0"
                    + "&filter=cmis%3Anone";
            var childrenDoc = fs.session().getDocument(childrenUrl);
            if (childrenDoc.getInteger("/feed/numItems", -1) > MAX_ITEMS) {
                LOG.warn(
                        "TOO many items under {}. Will only process the "
                                + "first {}.",
                        cmisPath.path(), MAX_ITEMS);
            }
            var parentPath = cmisPath.path();
            for (Xml segXml : childrenDoc.getXMLList(
                    "/feed/entry/pathSegment/text()")) {
                var segment = segXml.getString(".");
                if (StringUtils.isNotBlank(segment)) {
                    var childPath = fs.getPath(
                            "/".equals(parentPath)
                                    ? "/" + segment
                                    : parentPath + "/" + segment);
                    if (filter == null || filter.accept(childPath)) {
                        children.add(childPath);
                    }
                }
            }
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
                && type != CmisFileAttributes.class) {
            throw new UnsupportedOperationException(type.getName());
        }
        var cmisPath = (CmisPath) path;
        var entry = cmisPath.getFileSystem().entry(cmisPath.path());
        var attrs = new CmisFileAttributes(entry);
        if (!attrs.isRecognized()) {
            throw new NoSuchFileException(path.toString());
        }
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
                    return CmisFileSystemProvider.this.readAttributes(
                            path, BasicFileAttributes.class);
                }

                @Override
                public void setTimes(
                        java.nio.file.attribute.FileTime lastModifiedTime,
                        java.nio.file.attribute.FileTime lastAccessTime,
                        java.nio.file.attribute.FileTime createTime) {
                    throw new UnsupportedOperationException(
                            "CMIS file systems are read-only.");
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
                "CMIS file systems do not expose file stores.");
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) {
        throw new UnsupportedOperationException(
                "CMIS file systems are read-only.");
    }

    @Override
    public void delete(Path path) {
        throw new UnsupportedOperationException(
                "CMIS file systems are read-only.");
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) {
        throw new UnsupportedOperationException(
                "CMIS file systems are read-only.");
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) {
        throw new UnsupportedOperationException(
                "CMIS file systems are read-only.");
    }

    @Override
    public void setAttribute(
            Path path, String attribute, Object value,
            LinkOption... options) {
        throw new UnsupportedOperationException(
                "CMIS file systems are read-only.");
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
