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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.norconex.crawler.fs.fetch.impl.ReadOnlyFileSystemProvider;

import jcifs.ACE;
import jcifs.CIFSContext;
import jcifs.smb.SmbFile;
import lombok.extern.slf4j.Slf4j;

/**
 * A read-only NIO.2 {@link FileSystemProvider} for SMB/CIFS shares, backed
 * directly by jcifs-ng's classic {@link SmbFile} API. Each
 * {@link SmbFileSystem} represents one authority (host + port); resource
 * paths within it map to SMB share + path.
 */
@Slf4j
final class SmbFileSystemProvider extends ReadOnlyFileSystemProvider {

    private static final int DEFAULT_PORT = 445;

    private final Map<String, SmbFileSystem> fileSystems =
            new ConcurrentHashMap<>();

    @Override
    public String getScheme() {
        return "smb";
    }

    @Override
    protected String fileSystemLabel() {
        return "SMB";
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
        throw new UnsupportedOperationException(
                "Use getOrCreateFileSystem(URI, CIFSContext) instead.");
    }

    /**
     * Atomically gets the already-open file system for this authority, or
     * creates and registers one if none exists yet. Safe to call
     * concurrently for the same authority.
     */
    SmbFileSystem getOrCreateFileSystem(URI uri, CIFSContext context) {
        return fileSystems.computeIfAbsent(
                key(uri),
                k -> new SmbFileSystem(
                        this, k, uri.getHost(), resolvePort(uri), context));
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
    public SmbPath getPath(URI uri) {
        var fs = (SmbFileSystem) getFileSystem(uri);
        return fs.getPath(StringUtils.defaultIfBlank(uri.getPath(), "/"));
    }

    void closeFileSystem(String key) {
        fileSystems.remove(key);
    }

    Collection<SmbFileSystem> openFileSystems() {
        return List.copyOf(fileSystems.values());
    }

    private static int resolvePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : DEFAULT_PORT;
    }

    private static String key(URI uri) {
        return uri.getHost() + ":" + resolvePort(uri);
    }

    // --- SmbFile construction ---------------------------------------------

    private static String url(
            SmbFileSystem fs, String path, boolean directoryHint) {
        try {
            var p = directoryHint && !path.endsWith("/") ? path + "/" : path;
            return new URI(
                    "smb", null, fs.host(), fs.port(), p, null, null)
                            .toString();
        } catch (URISyntaxException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    private static SmbFile openForStat(SmbFileSystem fs, String path)
            throws IOException {
        return new SmbFile(url(fs, path, false), fs.context());
    }

    private static SmbFile openForListing(SmbFileSystem fs, String path)
            throws IOException {
        return new SmbFile(url(fs, path, true), fs.context());
    }

    // --- Reads -------------------------------------------------------------

    @Override
    public InputStream newInputStream(Path path, OpenOption... options)
            throws IOException {
        var smbPath = (SmbPath) path;
        var fs = smbPath.getFileSystem();
        var file = openForStat(fs, smbPath.path());
        if (!file.exists()) {
            file.close();
            throw new NoSuchFileException(smbPath.path());
        }
        // Deliberately not closing `file` here: the returned stream opens
        // and owns its own SMB handle, but if strict resource lifecycle is
        // ever enabled, closing the parent SmbFile would tear down the
        // tree connection the stream depends on.
        return file.getInputStream();
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(
            Path dir, Filter<? super Path> filter) throws IOException {
        var smbPath = (SmbPath) dir;
        var fs = smbPath.getFileSystem();
        var dirFile = openForListing(fs, smbPath.path());

        List<Path> children = new ArrayList<>();
        try {
            if (!dirFile.exists()) {
                throw new NoSuchFileException(smbPath.path());
            }
            for (SmbFile child : dirFile.listFiles()) {
                try {
                    var name = Strings.CS.removeEnd(child.getName(), "/");
                    var isDir = child.isDirectory();
                    var childPath = fs.getPath(smbPath.path() + "/" + name);
                    var attrs = new SmbFileAttributes(
                            isDir, isDir ? 0 : child.length(),
                            child.lastModified());
                    fs.attrsCache().put(childPath.path(), attrs);
                    if (filter == null || filter.accept(childPath)) {
                        children.add(childPath);
                    }
                } finally {
                    child.close();
                }
            }
        } finally {
            dirFile.close();
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
                && type != SmbFileAttributes.class) {
            throw new UnsupportedOperationException(type.getName());
        }
        var smbPath = (SmbPath) path;
        var fs = smbPath.getFileSystem();

        var cached = fs.attrsCache().get(smbPath.path());
        if (cached != null) {
            return (A) cached;
        }

        var file = openForStat(fs, smbPath.path());
        try {
            if (!file.exists()) {
                throw new NoSuchFileException(smbPath.path());
            }
            var isDir = file.isDirectory();
            var attrs = new SmbFileAttributes(
                    isDir, isDir ? 0 : file.length(), file.lastModified());
            fs.attrsCache().put(smbPath.path(), attrs);
            return (A) attrs;
        } finally {
            file.close();
        }
    }

    // --- ACL -----------------------------------------------------------

    /**
     * Fetches the Windows ACL for the given resource. Mirrors the
     * directory-trailing-slash handling the prior VFS-based fetcher relied
     * on, since some SMB servers require it for security-descriptor
     * queries on folders.
     */
    ACE[] getAcl(SmbPath path) throws IOException {
        var fs = path.getFileSystem();
        var file = openForStat(fs, path.path());
        try {
            if (file.isDirectory()) {
                file.close();
                file = openForListing(fs, path.path());
            }
            return file.getSecurity();
        } finally {
            file.close();
        }
    }

}
