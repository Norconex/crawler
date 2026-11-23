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

import static com.norconex.crawler.fs.fetch.impl.FileFetchUtil.referenceStartsWith;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.azure.storage.common.StorageSharedKeyCredential;
import com.azure.storage.file.datalake.DataLakeFileSystemClient;
import com.azure.storage.file.datalake.DataLakeFileSystemClientBuilder;
import com.azure.storage.file.datalake.models.AccessControlType;
import com.azure.storage.file.datalake.models.PathAccessControl;
import com.azure.storage.file.datalake.models.PathAccessControlEntry;
import com.azure.storage.file.datalake.models.RolePermissions;
import com.norconex.committer.core.acl.AclLevel;
import com.norconex.commons.lang.encrypt.EncryptionUtil;
import com.norconex.commons.lang.map.Properties;
import com.norconex.crawler.core.fetch.FetchRequest;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.fs.fetch.acl.AclFetchMode;
import com.norconex.crawler.fs.fetch.acl.FsAcls;
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
 * Fetcher for Azure Data Lake Storage Gen2, using references such as
 * <code>abfss://filesystem@account.dfs.core.windows.net/path/to/file</code>.
 * </p>
 *
 * <h2>Access Control List (ACL)</h2>
 * <p>
 * When asked to (see {@link AdlsGen2FetcherConfig#setAcl(AclFetchMode)}),
 * this fetcher writes who may read each file in the standard
 * {@code crawler.acl} field, from the path's POSIX-style ACL: its
 * {@code ACCESS} entries granting {@code r}, the mask applied, with
 * {@code other} as everyone. A {@code path} level says who may go through
 * (has {@code x} on) every folder above the file, the file system's root
 * included. Users and groups are Microsoft Entra object ids, of the
 * directory {@code entra:<storage account>}. {@code DEFAULT} entries, which
 * only describe what new children get, are kept in the ACL's native part.
 * </p>
 * <p>
 * Azure role assignments (such as Storage Blob Data Reader) grant access
 * besides these ACLs and are not read: a person with such a role may read
 * files their ACLs do not show.
 * </p>
 */
@Slf4j
@ToString
@EqualsAndHashCode
public class AdlsGen2Fetcher extends AbstractNioFetcher<AdlsGen2FetcherConfig> {

    @Getter
    private final AdlsGen2FetcherConfig configuration =
            new AdlsGen2FetcherConfig();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final AdlsGen2FileSystemProvider provider =
            new AdlsGen2FileSystemProvider();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final TraverseLevels<String> traverseLevels =
            new TraverseLevels<>();

    @Override
    protected void fetcherShutdown(CrawlerSession crawler) {
        provider.openFileSystems().forEach(fs -> {
            try {
                fs.close();
            } catch (RuntimeException e) {
                LOG.warn("Could not close ADLS Gen2 file system.", e);
            }
        });
        traverseLevels.clear();
        super.fetcherShutdown(crawler);
    }

    @Override
    protected boolean acceptRequest(@NonNull FetchRequest fetchRequest) {
        return referenceStartsWith(fetchRequest, "abfs://", "abfss://");
    }

    @Override
    protected Path resolvePath(String reference) throws IOException {
        var location = AdlsGen2Location.from(URI.create(reference));
        var fs = provider.getOrCreateFileSystem(location, this::buildClient);
        return fs.getPath(location.path());
    }

    @Override
    protected void fetchMetadata(
            Doc doc, @NonNull Path path, @NonNull BasicFileAttributes attrs)
            throws IOException {
        super.fetchMetadata(doc, path, attrs);
        if (configuration.getAcl().isEnabled()) {
            fetchAcl((AdlsGen2Path) path, doc.getMetadata());
        }
    }

    void fetchAcl(AdlsGen2Path path, Properties meta) {
        var mode = configuration.getAcl();
        var fs = path.getFileSystem();
        try {
            var fileAcl = posixAcl(
                    fs, provider.accessControl(fs, path.pathName()));
            List<String> unread = new ArrayList<>();
            List<AclLevel> levels = new ArrayList<>();
            try {
                levels.addAll(traverseLevels.levels(
                        folders(path), key -> posixAcl(fs,
                                provider.accessControl(fs, folderName(key)))
                                        .level(TraverseLevels.LEVEL_NAME,
                                                PosixAcl.EXECUTE)));
            } catch (IOException e) {
                unread.add("folders above: " + e.getMessage());
            }
            levels.add(fileAcl.level("file", PosixAcl.READ));
            FsAcls.write(meta, mode, levels, fileAcl.owners(),
                    fileAcl.nativeData(), unread);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read the permissions of {}: {}",
                    path.toUri(), e.toString());
            LOG.debug("Could not read the permissions of {}.",
                    path.toUri(), e);
            FsAcls.writeNone(meta, mode, "file permissions: " + e);
        }
    }

    // the folders above a file, from the root down, keyed with their file
    // system so two file systems never share a folder's level
    private static List<String> folders(AdlsGen2Path path) {
        var fs = path.getFileSystem();
        var prefix = fs.host() + "/" + fs.fileSystemName() + "/";
        List<String> folders = new ArrayList<>();
        folders.add(prefix);
        var name = path.pathName();
        for (var idx = name.indexOf('/'); idx > 0;
                idx = name.indexOf('/', idx + 1)) {
            folders.add(prefix + name.substring(0, idx));
        }
        return folders;
    }

    private static String folderName(String key) {
        // host/fileSystem/name: the host and file system have no slash
        var idx = key.indexOf('/', key.indexOf('/') + 1);
        return key.substring(idx + 1);
    }

    PosixAcl posixAcl(AdlsGen2FileSystem fs, PathAccessControl ac) {
        var acl = new PosixAcl("entra:" + fs.account(),
                ac.getOwner() == null ? null : Principal.named(ac.getOwner()),
                ac.getGroup() == null ? null : Principal.named(ac.getGroup()),
                0);
        var perms = ac.getPermissions();
        if (perms != null) {
            acl.ownerPerms(bits(perms.getOwner()))
                    .groupPerms(bits(perms.getGroup()))
                    .otherPerms(bits(perms.getOther()));
        }
        var extended = configuration.getAcl() == AclFetchMode.EXTENDED;
        var entries = ac.getAccessControlList() == null
                ? List.<PathAccessControlEntry>of()
                : ac.getAccessControlList();
        for (PathAccessControlEntry e : entries) {
            if (e == null) {
                continue;
            }
            if (e.isInDefaultScope()) {
                acl.nativeEntry(PathAccessControlEntry.serializeList(
                        List.of(e)));
                continue;
            }
            var type = e.getAccessControlType();
            var bits = bits(e.getPermissions());
            var named = StringUtils.isNotBlank(e.getEntityId());
            if (AccessControlType.USER.equals(type)) {
                if (!named) {
                    acl.ownerPerms(bits);
                } else if (extended) {
                    acl.user(Principal.named(e.getEntityId()), bits);
                }
            } else if (AccessControlType.GROUP.equals(type)) {
                if (!named) {
                    acl.groupPerms(bits);
                } else if (extended) {
                    acl.group(Principal.named(e.getEntityId()), bits);
                }
            } else if (AccessControlType.OTHER.equals(type)) {
                acl.otherPerms(bits);
            } else if (AccessControlType.MASK.equals(type) && extended) {
                acl.mask(bits);
            }
        }
        return acl;
    }

    private static int bits(RolePermissions perms) {
        if (perms == null) {
            return 0;
        }
        return (perms.hasReadPermission() ? PosixAcl.READ : 0)
                | (perms.hasWritePermission() ? PosixAcl.WRITE : 0)
                | (perms.hasExecutePermission() ? PosixAcl.EXECUTE : 0);
    }

    DataLakeFileSystemClient buildClient(AdlsGen2Location location) {
        var cfg = configuration;
        var creds = cfg.getCredentials();
        var account = location.account();
        if (creds.isSet()
                && StringUtils.isNotBlank(creds.getUsername())
                && !Strings.CI.equals(creds.getUsername(),
                        account)) {
            throw new IllegalArgumentException(
                    "ADLS Gen2 credentials username must match the "
                            + "reference account: " + account);
        }

        var builder = newClientBuilder()
                .endpoint(resolveEndpoint(location))
                .fileSystemName(location.fileSystem());

        if (StringUtils.isNotBlank(cfg.getSasToken())) {
            builder.sasToken(Strings.CS.removeStart(cfg.getSasToken(), "?"));
        } else if (creds.isSet() && StringUtils.isBlank(creds.getUsername())) {
            // No account name: the (possibly encrypted) password holds a SAS
            // token, so it can be kept like any other secret.
            builder.sasToken(Strings.CS.removeStart(
                    EncryptionUtil.decryptPassword(creds), "?"));
        } else if (creds.isSet()) {
            builder.credential(new StorageSharedKeyCredential(
                    account,
                    EncryptionUtil.decryptPassword(creds)));
        }
        return builder.buildClient();
    }

    DataLakeFileSystemClientBuilder newClientBuilder() {
        return new DataLakeFileSystemClientBuilder();
    }

    private String resolveEndpoint(AdlsGen2Location location) {
        var endpoint = configuration.getEndpoint();
        if (StringUtils.isNotBlank(endpoint)) {
            return endpoint;
        }
        return "https://%s.dfs.core.windows.net"
                .formatted(location.account().toLowerCase(Locale.ROOT));
    }
}
