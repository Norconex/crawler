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
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

import com.norconex.commons.lang.map.Properties;
import com.norconex.crawler.core.fetch.FetchRequest;
import com.norconex.crawler.fs.doc.FsDocMetadata;
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
 * This fetcher will try to extract access control information for each file
 * of a local file system. If you have no need for them, you can disable
 * acquiring them with {@link LocalFetcherConfig#setAclDisabled(boolean)}.
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
    protected void fetchMetadata(
            Doc doc, @NonNull Path path,
            @NonNull BasicFileAttributes attrs)
            throws IOException {
        super.fetchMetadata(doc, path, attrs);

        if (!configuration.isAclDisabled()) {
            fetchAcl(path, doc.getMetadata());
        }
    }

    void fetchAcl(Path path, Properties metadata) {
        try {
            var aclFileAttributes = Files.getFileAttributeView(
                    path, AclFileAttributeView.class);

            if (aclFileAttributes == null) {
                LOG.debug("No ACL file attributes on " + path);
                return;
            }

            if (aclFileAttributes.getOwner() != null
                    && aclFileAttributes.getOwner().getName() != null) {
                metadata.add(
                        FsDocMetadata.ACL + ".owner",
                        aclFileAttributes.getOwner().getName());
            }

            for (AclEntry aclEntry : aclFileAttributes.getAcl()) {
                var type = Objects.toString(aclEntry.type(), "[NOTYPE]");
                var principal = aclEntry.principal().getName();
                for (AclEntryPermission perm : aclEntry.permissions()) {
                    metadata.add(
                            FsDocMetadata.ACL + "." + type
                                    + "." + perm.name(),
                            principal);
                }
                for (AclEntryFlag flag : aclEntry.flags()) {
                    metadata.add(
                            FsDocMetadata.ACL + "." + type
                                    + ".flag." + flag.name(),
                            principal);
                }
            }
        } catch (IOException e) {
            LOG.error("Could not retreive ACL data.", e);
        }
    }
}
