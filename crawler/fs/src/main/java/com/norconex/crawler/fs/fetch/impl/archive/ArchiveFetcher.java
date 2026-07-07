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
package com.norconex.crawler.fs.fetch.impl.archive;

import static com.norconex.crawler.fs.fetch.impl.FileFetchUtil.referenceStartsWith;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.lang3.StringUtils;

import com.github.robtimus.filesystems.ftp.FTPEnvironment;
import com.github.robtimus.filesystems.sftp.SFTPEnvironment;
import com.norconex.commons.lang.encrypt.EncryptionUtil;
import com.norconex.crawler.core.fetch.FetchException;
import com.norconex.crawler.core.fetch.FetchRequest;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.fs.fetch.FolderPathsFetchRequest;
import com.norconex.crawler.fs.fetch.FolderPathsFetchResponse;
import com.norconex.crawler.fs.fetch.FsPath;
import com.norconex.crawler.fs.fetch.impl.AbstractNioFetcher;
import com.norconex.crawler.fs.fetch.impl.GenericFolderPathsFetchResponse;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Fetcher for archive file systems treated as virtual directory trees.
 * Each entry in the archive is crawled as an individual document, enabling
 * recursive traversal and per-document metadata extraction.
 * </p>
 *
 * <p>
 * This fetcher supports archives that wrap any inner file system, including
 * remote ones such as FTP or SFTP. When credentials are required to access
 * the inner file system (e.g., an FTP server hosting a ZIP file), configure
 * them via the {@link ArchiveFetcherConfig} — they are forwarded
 * automatically to the inner scheme.
 * </p>
 *
 * <h2>Supported archive schemes (outer layer)</h2>
 * <ul>
 *   <li>{@code zip://} — ZIP archives</li>
 *   <li>{@code jar://} — JAR files</li>
 *   <li>{@code tar://} — TAR archives</li>
 *   <li>{@code tgz://} — Gzip-compressed TAR (alias for {@code tar:gz://})</li>
 *   <li>{@code tbz2://} — Bzip2-compressed TAR (alias for
 *       {@code tar:bz2://})</li>
 *   <li>{@code gz://} / {@code gzip://} — Gzip-compressed single files</li>
 *   <li>{@code bz2://} / {@code bzip2://} — Bzip2-compressed single
 *       files</li>
 * </ul>
 *
 * <h2>Example URIs</h2>
 * <ul>
 *   <li>Local: {@code zip:file:///backups/data.zip!/}</li>
 *   <li>FTP: {@code zip:ftp://host/backups/data.zip!/}</li>
 *   <li>SFTP: {@code tgz:sftp://host/exports/dump.tar.gz!/}</li>
 *   <li>HTTP: {@code zip:https://example.com/release.zip!/}</li>
 * </ul>
 *
 * <p>
 * ZIP/JAR archives are mounted lazily via the JDK's built-in {@code zipfs}
 * provider (remote sources are first spooled to a local temporary file,
 * since {@code zipfs} requires true random access that remote NIO.2
 * providers such as FTP/SFTP do not support). TAR/GZIP/BZIP2 archives have
 * no such built-in provider and are instead fully extracted once to a
 * local temporary directory on first access, which is then treated like
 * any other local directory.
 * </p>
 */
@ToString
@EqualsAndHashCode
@Slf4j
public class ArchiveFetcher extends AbstractNioFetcher<ArchiveFetcherConfig> {

    private static final Set<String> ZIP_SCHEMES = Set.of("zip", "jar");
    private static final Set<String> TAR_SCHEMES = Set.of("tar", "tgz", "tbz2");

    @Getter
    private final ArchiveFetcherConfig configuration =
            new ArchiveFetcherConfig();

    @EqualsAndHashCode.Exclude
    private final Map<String, FileSystem> zipFileSystems =
            new ConcurrentHashMap<>();
    @EqualsAndHashCode.Exclude
    private final Set<Path> spooledTempFiles = ConcurrentHashMap.newKeySet();
    @EqualsAndHashCode.Exclude
    private final Map<String, Path> extractedArchiveDirs =
            new ConcurrentHashMap<>();

    @EqualsAndHashCode.Exclude
    private Map<String, Object> ftpEnv;
    @EqualsAndHashCode.Exclude
    private Map<String, Object> sftpEnv;

    @Override
    protected void fetcherStartup(CrawlerSession crawler) {
        super.fetcherStartup(crawler);
        var cfg = configuration;

        var ftp = new FTPEnvironment();
        var sftp = new SFTPEnvironment()
                .withConfig("StrictHostKeyChecking", "no");
        if (cfg.getCredentials().isSet()) {
            var username = cfg.getCredentials().getUsername();
            var password = EncryptionUtil.decryptPassword(
                    cfg.getCredentials());
            ftp.withCredentials(username, password.toCharArray());
            sftp.withUsername(username)
                    .withPassword(password.toCharArray());
        }
        ftpEnv = ftp;
        sftpEnv = sftp;
    }

    @Override
    protected void fetcherShutdown(CrawlerSession crawler) {
        zipFileSystems.values().forEach(fs -> {
            try {
                fs.close();
            } catch (IOException e) {
                LOG.warn("Could not close zip file system: {}", fs, e);
            }
        });
        zipFileSystems.clear();
        spooledTempFiles.forEach(this::deleteQuietly);
        spooledTempFiles.clear();
        extractedArchiveDirs.values().forEach(this::deleteRecursivelyQuietly);
        extractedArchiveDirs.clear();
        super.fetcherShutdown(crawler);
    }

    @Override
    protected boolean acceptRequest(@NonNull FetchRequest fetchRequest) {
        return referenceStartsWith(
                fetchRequest,
                "bzip2:", "bz2:", "gzip:", "gz:", "jar:",
                "tar:", "tgz:", "tbz2:", "zip:");
    }

    @Override
    protected Path resolvePath(String reference) throws IOException {
        var parsed = parse(reference);
        if (ZIP_SCHEMES.contains(parsed.outerScheme())) {
            return resolveZipPath(parsed.innerRef(), parsed.pathInArchive());
        }
        return resolveExtractedPath(
                parsed.outerScheme(), parsed.innerRef(),
                parsed.pathInArchive());
    }

    private record ParsedReference(
            String outerScheme, String innerRef, String pathInArchive) {
    }

    private ParsedReference parse(String reference) {
        var colonIdx = reference.indexOf(':');
        var outerScheme = reference.substring(0, colonIdx).toLowerCase();
        var rest = reference.substring(colonIdx + 1);
        var bangIdx = rest.indexOf('!');
        var innerRef = bangIdx < 0 ? rest : rest.substring(0, bangIdx);
        var pathInArchive = StringUtils.defaultIfBlank(
                bangIdx < 0 ? null : rest.substring(bangIdx + 1), "/");
        return new ParsedReference(outerScheme, innerRef, pathInArchive);
    }

    // TAR/GZIP/BZIP2 archives are extracted to a real local temp
    // directory (see resolveExtractedPath/extractToTempDir), so unlike
    // ZIP/JAR (mounted via zipfs, whose Path#toUri() is self-describing,
    // e.g. "jar:file:/data.zip!/entry"), a child Path's own toUri() would
    // point at the raw, ephemeral temp location instead of a stable
    // "outerScheme:innerRef!/path" reference. Rebuild proper references
    // here instead of relying on the generic implementation.
    @Override
    protected FolderPathsFetchResponse fetchChildPaths(
            FolderPathsFetchRequest req) throws FetchException {
        var parentRef = req.getDoc().getReference();
        var parsed = parse(parentRef);
        if (ZIP_SCHEMES.contains(parsed.outerScheme())) {
            return super.fetchChildPaths(req);
        }
        try {
            var rootDir = getExtractedRootDir(
                    parsed.outerScheme(), parsed.innerRef());
            var currentDir = resolveExtractedPath(
                    parsed.outerScheme(), parsed.innerRef(),
                    parsed.pathInArchive());
            Set<FsPath> childPaths = new HashSet<>();
            try (DirectoryStream<Path> stream =
                    Files.newDirectoryStream(currentDir)) {
                for (Path child : stream) {
                    var relativeToRoot = rootDir.relativize(child)
                            .toString().replace('\\', '/');
                    var childRef = parsed.outerScheme() + ":"
                            + parsed.innerRef() + "!/" + relativeToRoot;
                    var fsPath = new FsPath(childRef);
                    fsPath.setFile(Files.isRegularFile(child));
                    fsPath.setFolder(Files.isDirectory(child));
                    childPaths.add(fsPath);
                }
            }
            return GenericFolderPathsFetchResponse.builder()
                    .processingOutcome(ProcessingOutcome.NEW)
                    .childPaths(childPaths)
                    .build();
        } catch (IOException e) {
            throw new FetchException(
                    "Could not fetch child paths of: " + parentRef, e);
        }
    }

    // --- ZIP/JAR: lazily mounted via the JDK's built-in zipfs -----------

    private Path resolveZipPath(String innerRef, String pathInArchive)
            throws IOException {
        try {
            var fs = zipFileSystems.computeIfAbsent(innerRef, ref -> {
                try {
                    return openZipFileSystem(ref);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            return fs.getPath(pathInArchive);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private FileSystem openZipFileSystem(String innerRef) throws IOException {
        Path archivePath;
        if (isLocalInnerRef(innerRef)) {
            archivePath = toLocalPath(innerRef);
        } else {
            // zipfs needs true random-access seeking that remote NIO.2
            // channels do not support (confirmed: sftp-fs's
            // SeekableByteChannel does not implement position()), so the
            // whole archive is spooled to a local temp file first.
            var tempFile = Files.createTempFile("nx-archive-", ".zip");
            spooledTempFiles.add(tempFile);
            try (var in = openInnerStream(innerRef)) {
                Files.copy(in, tempFile,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            archivePath = tempFile;
        }
        return FileSystems.newFileSystem(
                archivePath, Collections.<String, Object>emptyMap());
    }

    // --- TAR/GZIP/BZIP2: extracted once to a local temp directory -------

    private Path resolveExtractedPath(
            String outerScheme, String innerRef, String pathInArchive)
            throws IOException {
        var dir = getExtractedRootDir(outerScheme, innerRef);
        var relative = pathInArchive.startsWith("/")
                ? pathInArchive.substring(1)
                : pathInArchive;
        return relative.isEmpty() ? dir : dir.resolve(relative);
    }

    private Path getExtractedRootDir(String outerScheme, String innerRef)
            throws IOException {
        var key = outerScheme + "|" + innerRef;
        try {
            return extractedArchiveDirs.computeIfAbsent(key, k -> {
                try {
                    return extractToTempDir(outerScheme, innerRef);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private Path extractToTempDir(String outerScheme, String innerRef)
            throws IOException {
        var dir = Files.createTempDirectory("nx-archive-");
        try (var rawIn = new BufferedInputStream(openInnerStream(innerRef));
                var in = wrapCompressorStream(outerScheme, rawIn)) {
            if (TAR_SCHEMES.contains(outerScheme)) {
                extractTar(in, dir);
            } else {
                // single-file gz/bz2 (no tar layer): the decompressed
                // stream is the file content directly.
                var target = dir.resolve(singleFileEntryName(innerRef));
                try (var out = Files.newOutputStream(target)) {
                    in.transferTo(out);
                }
            }
        }
        return dir;
    }

    private InputStream wrapCompressorStream(
            String outerScheme, InputStream in) throws IOException {
        return switch (outerScheme) {
            case "tgz", "gz", "gzip" -> new GzipCompressorInputStream(in);
            case "tbz2", "bz2", "bzip2" -> new BZip2CompressorInputStream(in);
            default -> in; // plain "tar": no compression layer
        };
    }

    private void extractTar(InputStream in, Path destDir) throws IOException {
        try (var tarIn = new TarArchiveInputStream(in)) {
            var entry = tarIn.getNextEntry();
            while (entry != null) {
                var target = safeResolve(destDir, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (var out = Files.newOutputStream(target)) {
                        tarIn.transferTo(out);
                    }
                }
                entry = tarIn.getNextEntry();
            }
        }
    }

    // Guards against "zip-slip": an archive entry name containing ".."
    // that would otherwise let extraction escape the destination
    // directory.
    private Path safeResolve(Path destDir, String entryName)
            throws IOException {
        var target = destDir.resolve(entryName).normalize();
        if (!target.startsWith(destDir)) {
            throw new IOException(
                    "Archive entry escapes destination directory: "
                            + entryName);
        }
        return target;
    }

    private String singleFileEntryName(String innerRef) {
        var path = innerRef.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        var fileName = path.substring(path.lastIndexOf('/') + 1);
        var withoutExt = fileName.replaceFirst(
                "(?i)\\.(gz|gzip|bz2|bzip2)$", "");
        return StringUtils.defaultIfBlank(withoutExt, "content");
    }

    // --- Inner reference resolution (local / FTP / SFTP / HTTP(S)) ------

    private boolean isLocalInnerRef(String ref) {
        return ref.startsWith("/") || ref.startsWith("\\")
                || ref.regionMatches(true, 0, "file:", 0, 5)
                || ref.matches("(?i)^[a-z]{1,2}:[/\\\\].*");
    }

    private Path toLocalPath(String ref) {
        if (ref.regionMatches(true, 0, "file:", 0, 5)) {
            return Path.of(URI.create(ref));
        }
        return Path.of(ref);
    }

    private InputStream openInnerStream(String innerRef) throws IOException {
        if (isLocalInnerRef(innerRef)) {
            return Files.newInputStream(toLocalPath(innerRef));
        }
        if (innerRef.regionMatches(true, 0, "ftp:", 0, 4)
                || innerRef.regionMatches(true, 0, "ftps:", 0, 5)) {
            return Files.newInputStream(resolveRemoteInnerPath(
                    innerRef, ftpEnv));
        }
        if (innerRef.regionMatches(true, 0, "sftp:", 0, 5)) {
            return Files.newInputStream(resolveRemoteInnerPath(
                    innerRef, sftpEnv));
        }
        if (innerRef.regionMatches(true, 0, "http:", 0, 5)
                || innerRef.regionMatches(true, 0, "https:", 0, 6)) {
            return URI.create(innerRef).toURL().openStream();
        }
        throw new IOException(
                "Unsupported inner file system reference: " + innerRef);
    }

    private Path resolveRemoteInnerPath(String innerRef, Map<String, ?> env)
            throws IOException {
        var uri = URI.create(innerRef);
        var fs = getOrOpenFileSystem(uri, env);
        return fs.getPath(uri.getPath());
    }

    // --- Cleanup helpers --------------------------------------------------

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.warn("Could not delete temporary file: {}", path, e);
        }
    }

    private void deleteRecursivelyQuietly(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(this::deleteQuietly);
        } catch (IOException e) {
            LOG.warn("Could not delete temporary directory: {}", dir, e);
        }
    }
}
