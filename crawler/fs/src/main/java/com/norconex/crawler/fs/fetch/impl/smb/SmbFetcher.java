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

import static com.norconex.crawler.fs.fetch.impl.FileFetchUtil.referenceStartsWith;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.encrypt.EncryptionUtil;
import com.norconex.commons.lang.map.Properties;
import com.norconex.crawler.core.fetch.FetchRequest;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.committer.core.acl.AclLevel;
import com.norconex.crawler.fs.fetch.acl.AclFetchMode;
import com.norconex.crawler.fs.fetch.acl.FsAcls;
import com.norconex.crawler.fs.fetch.acl.FsAcls.OnceWarnings;
import com.norconex.crawler.fs.fetch.acl.TraverseLevels;
import com.norconex.crawler.fs.fetch.impl.AbstractNioFetcher;
import com.norconex.importer.doc.Doc;

import jcifs.ACE;
import jcifs.CIFSContext;
import jcifs.context.SingletonContext;
import jcifs.smb.NtlmPasswordAuthenticator;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * CIFS fetcher (Samba, Windows share) (<code>smb://</code>), backed by a
 * custom, read-only NIO.2 {@link java.nio.file.spi.FileSystemProvider}
 * ({@link SmbFileSystemProvider}) built directly on jcifs-ng's classic
 * {@code SmbFile} API.
 * </p>
 *
 * <h2>Access Control List (ACL)</h2>
 * <p>
 * When asked to (see {@link SmbFetcherConfig#setAcl(AclFetchMode)}), this
 * fetcher writes who may read each file in the standard
 * {@code crawler.acl} field: the share's permissions
 * ({@link AclFetchMode#EXTENDED} only) and the file's, each a level a person
 * must pass. An entry allowing or denying the read-data right is an allow
 * or a deny. Principals are SIDs, with their account names when the server
 * resolves them. On a Linux server shared through Samba (see
 * {@link SmbFetcherConfig#setLinuxServer(boolean)}), a {@code path} level
 * says who may go through the folders above the file.
 * </p>
 */
@Slf4j
@ToString
@EqualsAndHashCode
public class SmbFetcher extends AbstractNioFetcher<SmbFetcherConfig> {

    @Getter
    private final SmbFetcherConfig configuration = new SmbFetcherConfig();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final SmbFileSystemProvider provider = new SmbFileSystemProvider();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final TraverseLevels<String> traverseLevels =
            new TraverseLevels<>();

    // a share's level, or why it could not be read
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final Map<String, Object> shareLevels = new ConcurrentHashMap<>();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final OnceWarnings warnings = new OnceWarnings();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private volatile boolean resolveSids = true;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private CIFSContext context;

    @Override
    protected void fetcherStartup(CrawlerSession crawler) {
        super.fetcherStartup(crawler);
        var base = SingletonContext.getInstance();
        var creds = configuration.getCredentials();
        context = creds.isSet()
                ? base.withCredentials(new NtlmPasswordAuthenticator(
                        configuration.getDomain(), creds.getUsername(),
                        EncryptionUtil.decryptPassword(creds)))
                : base;
    }

    @Override
    protected void fetcherShutdown(CrawlerSession crawler) {
        provider.openFileSystems().forEach(SmbFileSystem::close);
        traverseLevels.clear();
        shareLevels.clear();
        warnings.clear();
        super.fetcherShutdown(crawler);
    }

    @Override
    protected boolean acceptRequest(@NonNull FetchRequest fetchRequest) {
        return referenceStartsWith(fetchRequest, "smb://");
    }

    @Override
    protected Path resolvePath(String reference) throws IOException {
        var uri = URI.create(reference);
        var fs = provider.getOrCreateFileSystem(uri, context);
        return fs.getPath(StringUtils.defaultIfBlank(uri.getPath(), "/"));
    }

    @Override
    protected void fetchMetadata(
            Doc doc, @NonNull Path path, @NonNull BasicFileAttributes attrs)
            throws IOException {
        super.fetchMetadata(doc, path, attrs);

        if (configuration.getAcl().isEnabled()) {
            fetchAcl((SmbPath) path, doc.getMetadata());
        }
    }

    void fetchAcl(SmbPath path, Properties meta) {
        var mode = configuration.getAcl();
        List<String> unread = new ArrayList<>();
        List<AclLevel> levels = new ArrayList<>();
        Map<String, Object> nativeData = new LinkedHashMap<>();
        try {
            if (mode == AclFetchMode.EXTENDED) {
                var share = shareLevel(path);
                if (share instanceof AclLevel level) {
                    levels.add(level);
                } else {
                    unread.add("share permissions: " + share);
                }
            }
            if (configuration.isLinuxServer()) {
                try {
                    levels.addAll(traverseLevels.levels(
                            folders(path), this::folderLevel));
                } catch (IOException e) {
                    unread.add("folders above: " + e.getMessage());
                }
            }
            var aces = aces(path);
            levels.add(SmbAcls.level("file", aces, SmbAcls.READ_RIGHTS));
            nativeData.put("file", SmbAcls.nativeEntries(aces));
            FsAcls.write(meta, mode, levels, null, nativeData, unread);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read the permissions of {}: {}",
                    path.toUri(), e.toString());
            LOG.debug("Could not read the permissions of {}.",
                    path.toUri(), e);
            FsAcls.writeNone(meta, mode, "file permissions: " + e);
        }
    }

    // the folders within the share above a file, from the share down
    private List<String> folders(SmbPath path) {
        List<String> folders = new ArrayList<>();
        var p = path.path();
        var share = SmbFileSystemProvider.shareRoot(path);
        for (var idx = p.lastIndexOf('/'); idx >= share.length();
                idx = p.lastIndexOf('/', idx - 1)) {
            folders.add(0, key(path, p.substring(0, idx)));
        }
        return folders;
    }

    private static String key(SmbPath path, String folder) {
        var fs = path.getFileSystem();
        return fs.host() + ":" + fs.port() + folder;
    }

    private AclLevel folderLevel(String key) throws IOException {
        var folder = key.substring(key.indexOf('/'));
        var hostPort = key.substring(0, key.indexOf('/'));
        var fs = provider.openFileSystems().stream()
                .filter(f -> hostPort.equals(f.host() + ":" + f.port()))
                .findFirst()
                .orElseThrow(() -> new IOException(
                        "No open SMB connection to " + hostPort));
        return SmbAcls.level(TraverseLevels.LEVEL_NAME,
                aces(fs.getPath(folder)), SmbAcls.TRAVERSE_RIGHTS);
    }

    private Object shareLevel(SmbPath path) {
        var key = key(path, SmbFileSystemProvider.shareRoot(path));
        return shareLevels.computeIfAbsent(key, k -> {
            try {
                return SmbAcls.level("share",
                        provider.getShareAcl(path, resolveSids),
                        SmbAcls.READ_RIGHTS);
            } catch (IOException | RuntimeException e) {
                warnings.warn(LOG, "share:" + k, """
                    Could not read the permissions of share {}, so the \
                    permissions of its files are partial (a committer \
                    will not send them unless its ACL "partial" setting is \
                    SEND). Reading a share's permissions usually needs an \
                    account with administrative rights on the server; set \
                    the fetcher's "acl" to BASIC to read only the files' \
                    own. Cause: {}""", k, e.toString());
                return "could not be read (" + e.getMessage() + ")";
            }
        });
    }

    // Asks for the SIDs' names, unless the server would not give them once.
    private ACE[] aces(SmbPath path) throws IOException {
        if (resolveSids) {
            try {
                return provider.getAcl(path, true);
            } catch (IOException e) {
                // fails below too if resolving names was not the problem
                var aces = provider.getAcl(path, false);
                resolveSids = false;
                warnings.warn(LOG, "resolveSids",
                        "The SMB server would not resolve the names of the "
                                + "accounts in its permissions, so they are "
                                + "known by their SIDs only, of an undefined "
                                + "kind. Cause: {}",
                        e.toString());
                return aces;
            }
        }
        return provider.getAcl(path, false);
    }
}
