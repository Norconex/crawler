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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.spi.FileSystemProvider;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;

import com.azure.core.http.rest.PagedIterable;
import com.azure.storage.file.datalake.DataLakeFileSystemClient;
import com.azure.storage.file.datalake.models.DataLakeFileOpenInputStreamResult;
import com.azure.storage.file.datalake.models.DataLakeStorageException;
import com.azure.storage.file.datalake.models.ListPathsOptions;
import com.azure.storage.file.datalake.models.PathAccessControlEntry;
import com.azure.storage.file.datalake.models.PathItem;
import com.azure.storage.file.datalake.models.PathProperties;
import com.norconex.commons.lang.map.Properties;
import com.norconex.crawler.fs.doc.FsDocMetadata;
import com.norconex.crawler.fs.fetch.impl.ReadOnlyFileSystemProvider;

import lombok.extern.slf4j.Slf4j;

/**
 * A read-only NIO.2 {@link FileSystemProvider} for ADLS Gen2.
 */
@Slf4j
final class AdlsGen2FileSystemProvider extends ReadOnlyFileSystemProvider {

    private static final int HTTP_NOT_FOUND = 404;

    private final Map<String, AdlsGen2FileSystem> fileSystems =
            new ConcurrentHashMap<>();
    private volatile boolean aclDisabled;

    void setAclDisabled(boolean aclDisabled) {
        this.aclDisabled = aclDisabled;
    }

    @Override
    public String getScheme() {
        return "abfss";
    }

    @Override
    protected String fileSystemLabel() {
        return "ADLS Gen2";
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
        throw new UnsupportedOperationException(
                "Use getOrCreateFileSystem(AdlsGen2Location, Function) instead.");
    }

    AdlsGen2FileSystem getOrCreateFileSystem(
            AdlsGen2Location location,
            Function<AdlsGen2Location,
                    DataLakeFileSystemClient> clientFactory) {
        return fileSystems.computeIfAbsent(location.key(), key -> {
            LOG.info(
                    "Opening ADLS Gen2 file system: account={}, filesystem={}",
                    location.account(), location.fileSystem());
            return new AdlsGen2FileSystem(
                    this, location, clientFactory.apply(location));
        });
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        var location = AdlsGen2Location.from(uri);
        var fs = fileSystems.get(location.key());
        if (fs == null) {
            throw new FileSystemNotFoundException(uri.toString());
        }
        return fs;
    }

    @Override
    public AdlsGen2Path getPath(URI uri) {
        var location = AdlsGen2Location.from(uri);
        var fs = (AdlsGen2FileSystem) getFileSystem(uri);
        return fs.getPath(location.path());
    }

    void closeFileSystem(String key) {
        fileSystems.remove(key);
    }

    Collection<AdlsGen2FileSystem> openFileSystems() {
        return List.copyOf(fileSystems.values());
    }

    @Override
    public InputStream newInputStream(Path path, OpenOption... options)
            throws IOException {
        var adlsPath = (AdlsGen2Path) path;
        try {
            DataLakeFileOpenInputStreamResult result = adlsPath.getFileSystem()
                    .client()
                    .getFileClient(adlsPath.pathName())
                    .openInputStream();
            return result.getInputStream();
        } catch (DataLakeStorageException e) {
            if (e.getStatusCode() == HTTP_NOT_FOUND) {
                throw new NoSuchFileException(adlsPath.path());
            }
            throw new IOException(
                    "Could not read ADLS Gen2 file: " + adlsPath.path(), e);
        }
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(
            Path dir, Filter<? super Path> filter) throws IOException {
        var adlsPath = (AdlsGen2Path) dir;
        var fs = adlsPath.getFileSystem();

        List<Path> children = new ArrayList<>();
        try {
            var options = new ListPathsOptions()
                    .setPath(StringUtils.defaultIfBlank(adlsPath.pathName(),
                            null))
                    .setRecursive(false);
            PagedIterable<PathItem> iterable = fs.client().listPaths(options,
                    null);
            for (PathItem item : iterable) {
                if (item == null || StringUtils.isBlank(item.getName())) {
                    continue;
                }
                var isDirectory = Boolean.TRUE.equals(item.isDirectory());
                var childPath = fs.getPath("/" + item.getName());
                fs.attrsCache().put(
                        childPath.path(),
                        new AdlsGen2FileAttributes(
                                isDirectory,
                                isDirectory ? 0 : item.getContentLength(),
                                toFileTime(item.getLastModified())));
                if (filter == null || filter.accept(childPath)) {
                    children.add(childPath);
                }
            }
        } catch (DataLakeStorageException e) {
            if (e.getStatusCode() == HTTP_NOT_FOUND) {
                throw new NoSuchFileException(adlsPath.path());
            }
            throw new IOException(
                    "Could not list ADLS Gen2 paths under: " + adlsPath.path(),
                    e);
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
                && type != AdlsGen2FileAttributes.class) {
            throw new UnsupportedOperationException(type.getName());
        }

        var adlsPath = (AdlsGen2Path) path;
        var fs = adlsPath.getFileSystem();
        if (adlsPath.pathName().isEmpty()) {
            return (A) new AdlsGen2FileAttributes(true, 0, null);
        }

        var cached = fs.attrsCache().get(adlsPath.path());
        if (cached != null) {
            cacheAclIfEnabled(fs, adlsPath);
            return (A) cached;
        }

        var fileClient = fs.client().getFileClient(adlsPath.pathName());
        try {
            if (fileClient.exists()) {
                var props = fileClient.getProperties();
                var attrs = new AdlsGen2FileAttributes(
                        false,
                        props.getFileSize(),
                        toFileTime(props.getLastModified()));
                fs.attrsCache().put(adlsPath.path(), attrs);
                cacheAclIfEnabled(fs, adlsPath);
                return (A) attrs;
            }

            var dirClient = fs.client().getDirectoryClient(adlsPath.pathName());
            if (dirClient.exists()) {
                PathProperties props = dirClient.getProperties();
                var attrs = new AdlsGen2FileAttributes(
                        true,
                        0,
                        toFileTime(props.getLastModified()));
                fs.attrsCache().put(adlsPath.path(), attrs);
                cacheAclIfEnabled(fs, adlsPath);
                return (A) attrs;
            }
        } catch (DataLakeStorageException e) {
            if (e.getStatusCode() != HTTP_NOT_FOUND) {
                throw new IOException(
                        "Could not read ADLS Gen2 path attributes: "
                                + adlsPath.path(),
                        e);
            }
        }
        throw new NoSuchFileException(adlsPath.path());
    }

    private static FileTime toFileTime(OffsetDateTime time) {
        return time == null ? null : FileTime.from(time.toInstant());
    }

    Properties consumeAcl(String path) {
        for (var fs : fileSystems.values()) {
            var props = fs.aclCache().remove(path);
            if (props != null) {
                return props;
            }
        }
        return null;
    }

    private void cacheAclIfEnabled(AdlsGen2FileSystem fs, AdlsGen2Path path)
            throws IOException {
        if (aclDisabled || path.pathName().isEmpty()) {
            return;
        }
        var aclProps = new Properties();
        try {
            PathProperties props;
            var fileClient = fs.client().getFileClient(path.pathName());
            if (fileClient.exists()) {
                props = fileClient.getProperties();
            } else {
                var dirClient = fs.client().getDirectoryClient(path.pathName());
                if (!dirClient.exists()) {
                    return;
                }
                props = dirClient.getProperties();
            }
            if (props == null) {
                return;
            }
            if (StringUtils.isNotBlank(props.getOwner())) {
                aclProps.set(FsDocMetadata.ACL + ".owner", props.getOwner());
            }
            if (StringUtils.isNotBlank(props.getGroup())) {
                aclProps.set(FsDocMetadata.ACL + ".group", props.getGroup());
            }
            if (StringUtils.isNotBlank(props.getPermissions())) {
                aclProps.set(FsDocMetadata.ACL + ".permissions",
                        props.getPermissions());
            }
            if (props.getAccessControlList() != null) {
                for (PathAccessControlEntry ace : props
                        .getAccessControlList()) {
                    if (ace == null) {
                        continue;
                    }
                    var scope = StringUtils.defaultIfBlank(
                            Boolean.TRUE.equals(ace.isInDefaultScope())
                                    ? "DEFAULT"
                                    : null,
                            "ACCESS");
                    var type = StringUtils.defaultIfBlank(
                            ace.getAccessControlType() == null
                                    ? null
                                    : ace.getAccessControlType().toString(),
                            "NOTYPE");
                    var principal = StringUtils.defaultIfBlank(
                            ace.getEntityId(), "unknown");
                    var perms = ace.getPermissions() == null
                            ? ""
                            : ace.getPermissions().toString();
                    aclProps.add(
                            FsDocMetadata.ACL + "." + scope + "." + type
                                    + "." + perms,
                            principal);
                }
            }
            if (!aclProps.isEmpty()) {
                fs.aclCache().put(path.path(), aclProps);
            }
        } catch (DataLakeStorageException e) {
            if (e.getStatusCode() != HTTP_NOT_FOUND) {
                throw new IOException(
                        "Could not read ADLS Gen2 ACL data: " + path.path(),
                        e);
            }
        }
    }

}
