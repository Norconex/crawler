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
package com.norconex.crawler.fs.fetch.impl.sftp;

import static com.norconex.crawler.fs.fetch.impl.FileFetchUtil.referenceStartsWith;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.github.robtimus.filesystems.sftp.SFTPEnvironment;
import com.norconex.committer.core.acl.AclLevel;
import com.norconex.commons.lang.encrypt.EncryptionUtil;
import com.norconex.commons.lang.map.Properties;
import com.norconex.crawler.core.fetch.FetchRequest;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.fs.fetch.acl.AclFetchMode;
import com.norconex.crawler.fs.fetch.acl.FsAcls;
import com.norconex.crawler.fs.fetch.acl.FsAcls.OnceWarnings;
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
 * SFTP fetcher (<code>sftp://</code>), backed by the
 * <a href="https://github.com/robtimus/sftp-fs">sftp-fs</a> NIO.2
 * file system provider.
 * </p>
 *
 * <h2>Access Control List (ACL)</h2>
 * <p>
 * When asked to (see {@link SftpFetcherConfig#setAcl(AclFetchMode)}), this
 * fetcher writes who may read each file in the standard
 * {@code crawler.acl} field, from its owner, group and mode bits, with a
 * {@code path} level for who may go through every folder above it. SFTP
 * gives the owner and group as numbers (uid, gid), of the directory
 * {@code unix:<host>}. It cannot give extended ACLs.
 * </p>
 * @see com.norconex.crawler.fs.fetch.impl.ftp.FtpFetcher
 */
@Slf4j
@ToString
@EqualsAndHashCode
public class SftpFetcher extends AbstractNioFetcher<SftpFetcherConfig> {

    @Getter
    private final SftpFetcherConfig configuration = new SftpFetcherConfig();

    private Map<String, Object> env;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final TraverseLevels<String> traverseLevels =
            new TraverseLevels<>();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final OnceWarnings warnings = new OnceWarnings();

    @Override
    protected void fetcherStartup(CrawlerSession crawler) {
        super.fetcherStartup(crawler);
        var cfg = configuration;
        var sftpEnv = new SFTPEnvironment()
                .withConfig(
                        "StrictHostKeyChecking",
                        cfg.getStrictHostKeyChecking());
        if (cfg.getCredentials().isSet()) {
            sftpEnv.withUsername(cfg.getCredentials().getUsername())
                    .withPassword(EncryptionUtil.decryptPassword(
                            cfg.getCredentials()).toCharArray());
        }
        if (cfg.getConnectTimeout() != null) {
            sftpEnv.withConnectTimeout(
                    (int) cfg.getConnectTimeout().toMillis());
        }
        if (cfg.getKnownHosts() != null) {
            sftpEnv.withKnownHosts(cfg.getKnownHosts());
        }
        if (StringUtils.isNotBlank(cfg.getFileNameEncoding())) {
            sftpEnv.withFilenameEncoding(
                    Charset.forName(cfg.getFileNameEncoding()));
        }
        if (StringUtils.isNotBlank(cfg.getPreferredAuthentications())) {
            sftpEnv.withConfig(
                    "PreferredAuthentications",
                    cfg.getPreferredAuthentications());
        }
        if (StringUtils.isNotBlank(cfg.getCompression())) {
            sftpEnv.withConfig("compression.s2c", cfg.getCompression());
            sftpEnv.withConfig("compression.c2s", cfg.getCompression());
        }
        env = sftpEnv;
    }

    @Override
    protected boolean acceptRequest(@NonNull FetchRequest fetchRequest) {
        return referenceStartsWith(fetchRequest, "sftp://");
    }

    @Override
    protected Path resolvePath(String reference) throws IOException {
        var uri = URI.create(reference);
        var fs = getOrOpenFileSystem(uri, env);
        var path = uri.getPath();
        if (configuration.isUserDirIsRoot()
                && path != null
                && path.startsWith("/")) {
            // Reinterpret as relative to the connection's default
            // (home) directory rather than the true file system root.
            path = path.substring(1);
        }
        return fs.getPath(path);
    }

    @Override
    protected void fetcherShutdown(CrawlerSession crawler) {
        traverseLevels.clear();
        warnings.clear();
        super.fetcherShutdown(crawler);
    }

    @Override
    protected void fetchMetadata(
            Doc doc, @NonNull Path path, @NonNull BasicFileAttributes attrs)
            throws IOException {
        super.fetchMetadata(doc, path, attrs);
        if (configuration.getAcl().isEnabled()) {
            fetchAcl(path, attrs, doc.getMetadata());
        }
    }

    void fetchAcl(Path path, BasicFileAttributes attrs, Properties meta) {
        var mode = configuration.getAcl();
        if (!(attrs instanceof PosixFileAttributes posix)) {
            FsAcls.writeNone(meta, mode,
                    "the server gives no permissions");
            return;
        }
        var host = path.toUri().getHost();
        List<String> unread = new ArrayList<>();
        if (mode == AclFetchMode.EXTENDED) {
            warnings.warn(LOG, "extended", """
                SFTP cannot read extended ACLs, so the permissions of \
                every file are partial (a committer will not send them \
                unless its ACL "partial" setting is SEND). Set the \
                fetcher's "acl" to BASIC to read the owner, group and \
                mode bits, all that SFTP gives.""");
            unread.add("extended ACL: SFTP cannot read them");
        }
        List<AclLevel> levels = new ArrayList<>();
        try {
            Map<String, Path> folders = new LinkedHashMap<>();
            List<Path> above = new ArrayList<>();
            for (var p = path.toAbsolutePath().getParent(); p != null;
                    p = p.getParent()) {
                above.add(0, p);
            }
            above.forEach(f -> folders.put(f.toUri().toString(), f));
            levels.addAll(traverseLevels.levels(
                    List.copyOf(folders.keySet()),
                    key -> posixAcl(host, Files.readAttributes(
                            folders.get(key), PosixFileAttributes.class))
                                    .level(TraverseLevels.LEVEL_NAME,
                                            PosixAcl.EXECUTE)));
        } catch (IOException | RuntimeException e) {
            unread.add("folders above: " + e.getMessage());
        }
        var fileAcl = posixAcl(host, posix);
        levels.add(fileAcl.level("file", PosixAcl.READ));
        FsAcls.write(meta, mode, levels, fileAcl.owners(),
                fileAcl.nativeData(), unread);
    }

    static PosixAcl posixAcl(String host, PosixFileAttributes attrs) {
        return new PosixAcl(FsAcls.unixDir(host),
                attrs.owner() == null
                        ? null
                        : Principal.named(attrs.owner().getName()),
                attrs.group() == null
                        ? null
                        : Principal.named(attrs.group().getName()),
                PosixAcl.mode(attrs.permissions()));
    }
}
