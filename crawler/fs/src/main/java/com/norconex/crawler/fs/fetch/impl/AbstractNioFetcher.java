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
package com.norconex.crawler.fs.fetch.impl;

import static com.norconex.crawler.core.doc.CrawlerDocMetaConstants.PREFIX;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.norconex.commons.lang.file.ContentType;
import com.norconex.crawler.core.fetch.AbstractFetcher;
import com.norconex.crawler.core.fetch.BaseFetcherConfig;
import com.norconex.crawler.core.fetch.FetchDirective;
import com.norconex.crawler.core.fetch.FetchException;
import com.norconex.crawler.core.fetch.FetchRequest;
import com.norconex.crawler.core.fetch.FetchResponse;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.fs.doc.FsDocMetadata;
import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.crawler.fs.fetch.FileFetchResponse;
import com.norconex.crawler.fs.fetch.FolderPathsFetchRequest;
import com.norconex.crawler.fs.fetch.FolderPathsFetchResponse;
import com.norconex.crawler.fs.fetch.FsPath;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.doc.DocMetaConstants;

import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Base class for fetchers relying on Java NIO.2 ({@code java.nio.file}),
 * either via the JDK's built-in providers or third-party
 * {@link java.nio.file.spi.FileSystemProvider} implementations found on the
 * classpath.
 * </p>
 * @param <C> configuration type
 */
@Slf4j
@EqualsAndHashCode
@ToString
public abstract class AbstractNioFetcher<C extends BaseFetcherConfig>
        extends AbstractFetcher<C> {

    // Remote file systems (e.g., FTP/SFTP connections) are relatively
    // costly to establish, so they are opened once per authority
    // (scheme + host + port) and reused for the lifetime of this fetcher.
    // Local providers never populate this map. Excluded from equals/
    // hashCode: it is runtime connection state, not part of a fetcher's
    // configuration/identity.
    @EqualsAndHashCode.Exclude
    private final Map<String, FileSystem> openFileSystems =
            new ConcurrentHashMap<>();

    @Override
    protected void fetcherShutdown(CrawlerSession crawler) {
        openFileSystems.values().forEach(fs -> {
            try {
                fs.close();
            } catch (IOException e) {
                LOG.warn("Could not close file system: {}", fs, e);
            }
        });
        openFileSystems.clear();
    }

    @Override
    public FetchResponse fetch(FetchRequest fetchRequest)
            throws FetchException {
        if (fetchRequest instanceof FileFetchRequest fileReq) {
            return fetchFileObject(fileReq);
        }
        return fetchChildPaths((FolderPathsFetchRequest) fetchRequest);
    }

    /**
     * Resolves a crawler reference (as found on {@link Doc#getReference()})
     * into a NIO.2 {@link Path}.
     * @param reference the reference to resolve
     * @return the resolved path
     * @throws IOException if the reference cannot be resolved
     */
    protected abstract Path resolvePath(String reference) throws IOException;

    /**
     * Gets the {@link FileSystem} for the given URI's host and port,
     * opening and caching a new one on first use. Credentials always come
     * from the supplied environment, never from the URI: some providers
     * (e.g., sftp-fs/ftp-fs) echo the connected username back into the
     * user-info component of paths obtained from a listing (via
     * {@link Path#toUri()}), yet reject that same user-info component if
     * it is fed back into {@link FileSystems#newFileSystem(URI, Map)}. Any
     * user-info on the given URI is therefore ignored.
     * @param referenceUri a URI identifying the target file system (only
     *     its scheme, host and port are used)
     * @param env environment used only the first time a given host/port
     *     is resolved
     * @return the file system for that host and port
     * @throws IOException if the file system could not be opened
     */
    protected FileSystem getOrOpenFileSystem(
            URI referenceUri, Map<String, ?> env) throws IOException {
        var key = referenceUri.getScheme() + "://"
                + referenceUri.getHost()
                + (referenceUri.getPort() >= 0
                        ? ":" + referenceUri.getPort()
                        : "");
        try {
            return openFileSystems.computeIfAbsent(key, k -> {
                try {
                    return FileSystems.newFileSystem(
                            URI.create(k), env);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    protected FileFetchResponse fetchFileObject(FileFetchRequest req)
            throws FetchException {

        var doc = req.getDoc();
        var ref = doc.getReference();
        try {
            var path = resolvePath(ref);

            BasicFileAttributes attrs;
            try {
                attrs = readAttributes(path);
            } catch (NoSuchFileException e) {
                return GenericFileFetchResponse.builder()
                        .processingOutcome(ProcessingOutcome.NOT_FOUND)
                        .build();
            }

            var isFile = attrs.isRegularFile();
            var isFolder = attrs.isDirectory();

            if (isFile) {
                // Don't fetch body if we do meta only
                if (FetchDirective.DOCUMENT.is(req.getFetchDirective())) {
                    fetchContent(doc, path);
                }
                fetchMetadata(doc, path, attrs);
            }

            //TODO set status if not found or whatever bad state

            return GenericFileFetchResponse.builder()
                    .processingOutcome(ProcessingOutcome.NEW)
                    .file(isFile)
                    .folder(isFolder)
                    .build();
        } catch (IOException e) {
            throw new FetchException("Could not fetch reference: " + ref, e);
        }
    }

    // Fetches attributes in a single round-trip: some providers (e.g.,
    // sftp-fs) issue a fresh network request per Files.* attribute/access
    // call (exists, isRegularFile, isDirectory, isReadable, isHidden...),
    // so those are deliberately not used here. PosixFileAttributes is
    // requested first since it also carries permission bits, letting
    // fetchMetadata derive readable/writable/executable without extra
    // round-trips; providers without POSIX support (e.g., FTP, Windows
    // local) fall back to BasicFileAttributes.
    private BasicFileAttributes readAttributes(Path path) throws IOException {
        try {
            return Files.readAttributes(path, PosixFileAttributes.class);
        } catch (UnsupportedOperationException e) {
            return Files.readAttributes(path, BasicFileAttributes.class);
        }
    }

    protected FolderPathsFetchResponse fetchChildPaths(
            FolderPathsFetchRequest req) throws FetchException {
        var parentPath = req.getDoc().getReference();
        try {
            var dir = resolvePath(parentPath);
            Set<FsPath> childPaths = new HashSet<>();
            try (DirectoryStream<Path> stream =
                    Files.newDirectoryStream(dir)) {
                for (Path child : stream) {
                    var fsPath = new FsPath(
                            stripUserInfo(child.toUri()).toString());
                    fsPath.setFile(Files.isRegularFile(child));
                    fsPath.setFolder(Files.isDirectory(child));
                    childPaths.add(fsPath);
                }
            }
            return GenericFolderPathsFetchResponse.builder()
                    //TODO shall we care to put a real state here?
                    .processingOutcome(ProcessingOutcome.NEW)
                    .childPaths(childPaths)
                    .build();
        } catch (IOException e) {
            throw new FetchException(
                    "Could not fetch child paths of: " + parentPath, e);
        }
    }

    // Some providers (e.g., sftp-fs/ftp-fs) echo the connected username
    // back into the user-info component of Path#toUri(). Keep it out of
    // stored references: it is redundant with the fetcher's own
    // credentials and would otherwise leak the username into the target
    // repository.
    private static URI stripUserInfo(URI uri) {
        if (uri.getUserInfo() == null) {
            return uri;
        }
        try {
            return new URI(
                    uri.getScheme(), null, uri.getHost(), uri.getPort(),
                    uri.getPath(), uri.getQuery(), uri.getFragment());
        } catch (URISyntaxException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    protected void fetchMetadata(
            Doc doc, @NonNull Path path, @NonNull BasicFileAttributes attrs)
            throws IOException {

        var meta = doc.getMetadata();

        //--- Enhance Metadata ---
        meta.set(FsDocMetadata.FILE_SIZE, attrs.size());
        meta.set(
                FsDocMetadata.LAST_MODIFIED,
                attrs.lastModifiedTime().toMillis());

        // Cheap, filename-only content-type guess (no bytes read, no
        // network access) so it is available to metadata filters when a
        // METADATA-only fetch pass is configured, ahead of the (possibly
        // costly, e.g. remote) DOCUMENT fetch. Mirrors the VFS default
        // FileContentInfoFilenameFactory this replaces.
        var fileName = path.getFileName();
        var contentType = fileName == null
                ? null
                : URLConnection.getFileNameMap()
                        .getContentTypeFor(fileName.toString());
        if (contentType != null) {
            meta.set(DocMetaConstants.CONTENT_TYPE, contentType);
            doc.setContentType(ContentType.valueOf(contentType));
        }

        meta.set(PREFIX + "symbolicLink", attrs.isSymbolicLink());
        // isHidden always costs its own round-trip on remote providers
        // (no way around it), but avoid the 3 extra round-trips
        // isExecutable/isReadable/isWritable would each cost by deriving
        // owner permissions from the attributes already in hand whenever
        // POSIX support is available (local Unix, SFTP).
        if (attrs instanceof PosixFileAttributes posix) {
            var perms = posix.permissions();
            meta.set(PREFIX + "executable",
                    perms.contains(PosixFilePermission.OWNER_EXECUTE));
            meta.set(PREFIX + "readable",
                    perms.contains(PosixFilePermission.OWNER_READ));
            meta.set(PREFIX + "writable",
                    perms.contains(PosixFilePermission.OWNER_WRITE));
        } else {
            meta.set(PREFIX + "executable", Files.isExecutable(path));
            meta.set(PREFIX + "readable", Files.isReadable(path));
            meta.set(PREFIX + "writable", Files.isWritable(path));
        }
        meta.set(PREFIX + "hidden", Files.isHidden(path));
    }

    protected boolean fetchContent(Doc doc, @NonNull Path path)
            throws IOException {
        try (var is = doc.getStreamFactory().newInputStream(
                Files.newInputStream(path))) {
            is.enforceFullCaching();
            doc.setInputStream(is);
        }
        return true;
    }
}
