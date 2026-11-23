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
package com.norconex.crawler.fs.fetch.impl.local;

import static com.norconex.crawler.fs.fetch.impl.FileFetchUtil.referenceStartsWith;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.norconex.committer.core.acl.AclLevel;
import com.norconex.commons.lang.map.Properties;
import com.norconex.crawler.core.fetch.FetchRequest;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.fs.fetch.acl.AclFetchMode;
import com.norconex.crawler.fs.fetch.acl.FsAcls;
import com.norconex.crawler.fs.fetch.acl.FsAcls.OnceWarnings;
import com.norconex.crawler.fs.fetch.acl.NativePosixAcls;
import com.norconex.crawler.fs.fetch.acl.PosixAcl;
import com.norconex.crawler.fs.fetch.acl.PosixAcl.Principal;
import com.norconex.crawler.fs.fetch.acl.TraverseLevels;
import com.norconex.crawler.fs.fetch.impl.AbstractNioFetcher;
import com.norconex.importer.doc.Doc;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Fetcher for a local file system, backed by the JDK's default
 * {@link java.nio.file.spi.FileSystemProvider}. Mounted file systems and
 * mapped drives not requiring special configuration to access can also be
 * considered "local". Paths starting with any of the following will be
 * recognized as local file system:
 * </p>
 * <ul>
 *   <li>{@code file:///some/directory}</li>
 *   <li>{@code file:///C:/some/directory}</li>
 *   <li>{@code /some/directory}</li>
 *   <li>{@code C:\some\directory}</li>
 *   <li>{@code C:/some/directory}</li>
 * </ul>
 *
 * <h2>Access Control List (ACL)</h2>
 * <p>
 * When asked to (see {@link LocalFetcherConfig#setAcl(AclFetchMode)}), this
 * fetcher writes who may read each file in the standard
 * {@code crawler.acl} field:
 * </p>
 * <ul>
 *   <li><b>Windows</b>: the file's ACL, inherited entries included. An entry
 *       allowing or denying the read-data right is an allow or a deny.</li>
 *   <li><b>Linux and macOS</b>: the owner, group and mode bits and, with
 *       {@link AclFetchMode#EXTENDED}, the file's extended ACL (read from
 *       the operating system through JNA). A {@code path} level says who may
 *       go through every folder above the file, which reading it also
 *       needs. Users and groups are numbers (uid, gid) of the directory
 *       {@code unix:<host>}, with their names.</li>
 * </ul>
 * <p>
 * When extended ACLs cannot be read on the host, one warning is logged for
 * the crawl and the files' ACLs are partial.
 * </p>
 *
 * <h2>Archive files as file systems</h2>
 * <p>
 * Archive files (zip, tar, jar, etc.) are no longer handled by this fetcher.
 * Use {@link com.norconex.crawler.fs.fetch.impl.archive.ArchiveFetcher}
 * instead, which supports both local and remote archives and provides
 * credential configuration for inner remote file systems.
 * </p>
 */
@ToString
@EqualsAndHashCode
@Slf4j
public class LocalFetcher extends AbstractNioFetcher<LocalFetcherConfig> {

    @Getter
    private final LocalFetcherConfig configuration = new LocalFetcherConfig();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final TraverseLevels<Path> traverseLevels = new TraverseLevels<>();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final OnceWarnings warnings = new OnceWarnings();

    @Override
    protected boolean acceptRequest(@NonNull FetchRequest fetchRequest) {
        return referenceStartsWith(fetchRequest, "/", "\\", "file:")
                || fetchRequest.getDoc().getReference().matches(
                        "(?i)^[a-z]{1,2}:[/\\\\].*");
    }

    @Override
    protected Path resolvePath(String reference) {
        if (reference.regionMatches(true, 0, "file:", 0, 5)) {
            return Path.of(URI.create(reference));
        }
        return Path.of(reference);
    }

    @Override
    protected void fetcherShutdown(CrawlerSession crawler) {
        traverseLevels.clear();
        warnings.clear();
        super.fetcherShutdown(crawler);
    }

    @Override
    protected void fetchMetadata(
            Doc doc, @NonNull Path path,
            @NonNull BasicFileAttributes attrs)
            throws IOException {
        super.fetchMetadata(doc, path, attrs);

        if (configuration.getAcl().isEnabled()) {
            fetchAcl(path, attrs, doc.getMetadata());
        }
    }

    void fetchAcl(Path path, BasicFileAttributes attrs, Properties meta) {
        var mode = configuration.getAcl();
        try {
            var aclView = Files.getFileAttributeView(
                    path, AclFileAttributeView.class);
            if (aclView != null) {
                var read = WindowsAcls.read(aclView, computerName());
                FsAcls.write(meta, mode, List.of(read.level()),
                        read.owners(), read.nativeData(), null);
            } else if (attrs instanceof PosixFileAttributes posix) {
                fetchPosixAcl(path, posix, meta);
            } else {
                FsAcls.writeNone(meta, mode,
                        "the file system gives no permissions");
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read the permissions of {}: {}",
                    path, e.toString());
            LOG.debug("Could not read the permissions of {}.", path, e);
            FsAcls.writeNone(meta, mode, "file permissions: " + e);
        }
    }

    private void fetchPosixAcl(
            Path path, PosixFileAttributes attrs, Properties meta)
            throws IOException {
        List<String> unread = new ArrayList<>();
        var fileAcl = posixAcl(path, attrs, unread);

        List<Path> folders = new ArrayList<>();
        for (var p = path.toAbsolutePath().getParent(); p != null;
                p = p.getParent()) {
            folders.add(0, p);
        }
        List<AclLevel> levels = new ArrayList<>();
        try {
            levels.addAll(traverseLevels.levels(folders, folder -> {
                List<String> folderUnread = new ArrayList<>();
                var acl = posixAcl(folder, Files.readAttributes(
                        folder, PosixFileAttributes.class), folderUnread);
                // When extended ACLs cannot be read on this host at all, the
                // file is partial already: the folder's mode bits are kept
                // rather than read again for every file.
                if (!folderUnread.isEmpty()
                        && NativePosixAcls.unavailableReason() == null) {
                    throw new IOException(String.join("; ", folderUnread));
                }
                return acl.level(TraverseLevels.LEVEL_NAME, PosixAcl.EXECUTE);
            }));
        } catch (IOException e) {
            unread.add("folders above: " + e.getMessage());
        }
        levels.add(fileAcl.level("file", PosixAcl.READ));
        FsAcls.write(meta, configuration.getAcl(), levels, fileAcl.owners(),
                fileAcl.nativeData(), unread);
    }

    private PosixAcl posixAcl(
            Path path, PosixFileAttributes attrs, List<String> unread) {
        var owner = Principal.named(attrs.owner().getName());
        var group = Principal.named(attrs.group().getName());
        try {
            var ids = Files.readAttributes(path, "unix:uid,gid");
            owner = new Principal(String.valueOf(ids.get("uid")),
                    attrs.owner().getName());
            group = new Principal(String.valueOf(ids.get("gid")),
                    attrs.group().getName());
        } catch (IOException | UnsupportedOperationException
                | IllegalArgumentException e) {
            LOG.debug("No uid and gid for {}, using names.", path, e);
        }
        var acl = new PosixAcl(FsAcls.unixDir(FsAcls.localHostName()),
                owner, group, PosixAcl.mode(attrs.permissions()));
        if (configuration.getAcl() == AclFetchMode.EXTENDED) {
            var unavailable = NativePosixAcls.unavailableReason();
            if (unavailable != null) {
                warnings.warn(LOG, "native", """
                    Extended ACLs cannot be read on this host, so the \
                    permissions of every file are partial (a committer \
                    will not send them unless its ACL "partial" setting is \
                    SEND). Set the fetcher's "acl" to BASIC to read only \
                    the owner, group and mode bits. Cause: {}""",
                        unavailable);
                unread.add("extended ACL: " + unavailable);
            } else {
                try {
                    NativePosixAcls.read(path, acl);
                } catch (IOException e) {
                    unread.add("extended ACL: " + e.getMessage());
                }
            }
        }
        return acl;
    }

    private static String computerName() {
        return StringUtils.defaultIfBlank(
                System.getenv("COMPUTERNAME"), FsAcls.localHostName());
    }
}
