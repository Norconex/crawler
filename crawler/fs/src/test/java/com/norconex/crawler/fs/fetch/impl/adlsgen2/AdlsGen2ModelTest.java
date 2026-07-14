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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.AccessMode;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.azure.core.http.rest.PagedIterable;
import com.azure.storage.file.datalake.DataLakeDirectoryClient;
import com.azure.storage.file.datalake.DataLakeFileClient;
import com.azure.storage.file.datalake.DataLakeFileSystemClient;
import com.azure.storage.file.datalake.models.DataLakeFileOpenInputStreamResult;
import com.azure.storage.file.datalake.models.DataLakeStorageException;
import com.azure.storage.file.datalake.models.ListPathsOptions;
import com.azure.storage.file.datalake.models.PathAccessControlEntry;
import com.azure.storage.file.datalake.models.PathItem;
import com.azure.storage.file.datalake.models.PathProperties;
import com.norconex.crawler.fs.doc.FsDocMetadata;

class AdlsGen2ModelTest {

    @Test
    void testLocationParsingAndValidation() {
        var location = AdlsGen2Location.from(URI.create(
                "abfss://myfs@acct.dfs.core.windows.net/dir/file.txt"));
        assertThat(location.scheme()).isEqualTo("abfss");
        assertThat(location.fileSystem()).isEqualTo("myfs");
        assertThat(location.account()).isEqualTo("acct");
        assertThat(location.host()).isEqualTo("acct.dfs.core.windows.net");
        assertThat(location.path()).isEqualTo("/dir/file.txt");
        assertThat(location.key())
                .isEqualTo("abfss://myfs@acct.dfs.core.windows.net");

        assertThatThrownBy(() -> AdlsGen2Location.from(
                URI.create("abfss://acct.dfs.core.windows.net/path")))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("file system");
    }

    @Test
    void testFileAttributesDefaultsAndFlags() {
        var attrs = new AdlsGen2FileAttributes(true, -1, null);

        assertThat(attrs.isDirectory()).isTrue();
        assertThat(attrs.isRegularFile()).isFalse();
        assertThat(attrs.isSymbolicLink()).isFalse();
        assertThat(attrs.isOther()).isFalse();
        assertThat(attrs.size()).isZero();
        assertThat(attrs.lastModifiedTime()).isEqualTo(FileTime.fromMillis(0));
        assertThat(attrs.lastAccessTime()).isEqualTo(FileTime.fromMillis(0));
        assertThat(attrs.creationTime()).isEqualTo(FileTime.fromMillis(0));
        assertThat(attrs.fileKey()).isNull();
    }

    @Test
    void testFileSystemProviderAndPathOperations() {
        var provider = new AdlsGen2FileSystemProvider();
        var location = AdlsGen2Location.from(URI.create(
                "abfss://myfs@acct.dfs.core.windows.net/root"));
        var client = mock(DataLakeFileSystemClient.class);
        var fs = provider.getOrCreateFileSystem(location, loc -> client);

        var lookupUri = URI.create(
                "abfss://myfs@acct.dfs.core.windows.net/root");
        assertThat(provider.openFileSystems()).hasSize(1);
        assertThat(provider.getFileSystem(lookupUri)).isSameAs(fs);
        assertThat(provider.getPath(lookupUri).toString()).isEqualTo("/root");
        assertThat(fs.scheme()).isEqualTo("abfss");
        assertThat(fs.fileSystemName()).isEqualTo("myfs");
        assertThat(fs.account()).isEqualTo("acct");
        assertThat(fs.host()).isEqualTo("acct.dfs.core.windows.net");

        var root = fs.getPath("/");
        var child = fs.getPath("/a/b/c.txt");
        var sibling = fs.getPath("/a/b/d.txt");

        assertThat(fs.isOpen()).isTrue();
        assertThat(fs.isReadOnly()).isTrue();
        assertThat(fs.getSeparator()).isEqualTo("/");
        assertThat(fs.supportedFileAttributeViews()).containsExactly("basic");
        assertThat(fs.getRootDirectories()).containsExactly(root);
        assertThat(fs.getFileStores()).isEmpty();

        var glob = fs.getPathMatcher("glob:**/*.txt");
        assertThat(glob.matches(child)).isTrue();
        assertThatThrownBy(() -> fs.getPathMatcher("regex:.*"))
                .isInstanceOf(UnsupportedOperationException.class);

        var p = (AdlsGen2Path) child;
        assertThat(p.path()).isEqualTo("/a/b/c.txt");
        assertThat(p.pathName()).isEqualTo("a/b/c.txt");
        assertThat(p.getRoot()).isEqualTo(root);
        assertThat(p.getFileName().toString()).isEqualTo("/c.txt");
        assertThat(p.getParent().toString()).isEqualTo("/a/b");
        assertThat(p.getNameCount()).isEqualTo(3);
        assertThat(p.getName(1).toString()).isEqualTo("/b");
        assertThat(p.subpath(0, 2).toString()).isEqualTo("/a/b");
        assertThat(p.startsWith("/a")).isTrue();
        assertThat(p.endsWith("/c.txt")).isTrue();
        assertThat(p.normalize()).isSameAs(p);
        assertThat(p.resolveSibling(sibling)).isEqualTo(sibling);
        assertThat(p.relativize(sibling).toString()).isEqualTo("/../d.txt");
        assertThat(p.toUri().toString())
                .isEqualTo("abfss://myfs@acct.dfs.core.windows.net/a/b/c.txt");
        assertThat(p.toAbsolutePath()).isSameAs(p);
        assertThat(p.toRealPath()).isSameAs(p);
        assertThat((Iterable<Path>) p).hasSize(3);
        assertThat(p.compareTo(sibling)).isNegative();
        assertThat(p).isEqualTo(fs.getPath("/a/b/c.txt"));

        assertThatThrownBy(p::toFile)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> p.resolve(Path.of("x")))
                .isInstanceOf(IllegalArgumentException.class);

        fs.close();
        assertThat(fs.isOpen()).isFalse();
        assertThat(provider.openFileSystems()).isEmpty();
        assertThatThrownBy(() -> provider.getFileSystem(lookupUri))
                .isInstanceOf(FileSystemNotFoundException.class);
    }

    @Test
    void testProviderLifecycleAndLookup() {
        var provider = new AdlsGen2FileSystemProvider();
        var location = AdlsGen2Location.from(URI.create(
                "abfss://myfs@acct.dfs.core.windows.net/root"));
        var client = mock(DataLakeFileSystemClient.class);
        var fs = provider.getOrCreateFileSystem(location, loc -> client);

        assertThat(provider.openFileSystems()).hasSize(1);
        assertThat(provider.getFileSystem(
                URI.create("abfss://myfs@acct.dfs.core.windows.net/a/b")))
                        .isSameAs(fs);
        assertThat(provider.getPath(
                URI.create("abfss://myfs@acct.dfs.core.windows.net/a/b")))
                        .hasToString("/a/b");

        assertThatThrownBy(() -> provider.newFileSystem(
                URI.create("abfss://myfs@acct.dfs.core.windows.net/root"),
                Map.of()))
                        .isInstanceOf(UnsupportedOperationException.class)
                        .hasMessageContaining("getOrCreateFileSystem");

        var missing = URI.create("abfss://missing@acct.dfs.core.windows.net/x");
        assertThatThrownBy(() -> provider.getFileSystem(missing))
                .isInstanceOf(FileSystemNotFoundException.class);
        assertThatThrownBy(() -> provider.getPath(missing))
                .isInstanceOf(FileSystemNotFoundException.class);
    }

    @Test
    void testFileSystemAndPathEdgeCases() {
        var provider = new AdlsGen2FileSystemProvider();
        var client = mock(DataLakeFileSystemClient.class);
        var fs = provider.getOrCreateFileSystem(
                AdlsGen2Location.from(URI.create(
                        "abfss://myfs@acct.dfs.core.windows.net/root")),
                loc -> client);
        var otherFs = provider.getOrCreateFileSystem(
                AdlsGen2Location.from(URI.create(
                        "abfss://otherfs@acct.dfs.core.windows.net/root")),
                loc -> client);

        var root = fs.getPath("/");
        var leadingParent = fs.getPath("../a");
        var normalized = fs.getPath("/a/./b/../c");
        var child = fs.getPath("/a/b/c.txt");
        var cousin = fs.getPath("/a/x/c.txt");

        assertThat(root.getFileName()).isNull();
        assertThat(root.getParent()).isNull();
        assertThat(root.resolveSibling(child)).isEqualTo(child);
        assertThat(root.startsWith(child)).isFalse();
        assertThat(root.endsWith(child)).isFalse();
        assertThat(child.startsWith(otherFs.getPath("/a"))).isFalse();
        assertThat(child.endsWith(otherFs.getPath("/c.txt"))).isFalse();
        assertThat(child.startsWith(fs.getPath("/x"))).isFalse();
        assertThat(child.endsWith(fs.getPath("/x.txt"))).isFalse();
        assertThat(leadingParent).hasToString("/a");
        assertThat(normalized).hasToString("/a/c");
        assertThat(fs.getPath("/a", "b", "c.txt"))
                .isEqualTo(child);
        assertThat(child.resolve("/q/r.txt")).hasToString("/q/r.txt");
        assertThat(child.resolve("q/r.txt")).hasToString("/q/r.txt");
        assertThat(child.resolveSibling("/x/y/z.txt"))
                .hasToString("/x/y/z.txt");
        assertThat(child.relativize(cousin)).hasToString("/../../x/c.txt");
        assertThat(child.hashCode())
                .isEqualTo(fs.getPath("/a/b/c.txt").hashCode());
        assertThat(child.equals("not-a-path")).isFalse();
        assertThat(child).isNotEqualTo(cousin);
        assertThatThrownBy(() -> child.relativize(otherFs.getPath("/a/b")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> root.register(null, null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(fs::getUserPrincipalLookupService)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(fs::newWatchService)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> fs.getPathMatcher("glob"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testDirectoryListingCachesChildAttributes() throws Exception {
        var provider = new AdlsGen2FileSystemProvider();
        provider.setAclDisabled(true);
        var location = AdlsGen2Location.from(URI.create(
                "abfss://myfs@acct.dfs.core.windows.net/root"));
        var client = mock(DataLakeFileSystemClient.class);
        var fs = provider.getOrCreateFileSystem(location, loc -> client);

        var items = List.of(
                new PathItem("etag1", OffsetDateTime.now(), 7L,
                        null, false, "root/file.txt", null, null),
                new PathItem("etag2", OffsetDateTime.now(), 0L,
                        null, true, "root/folder", null, null));
        @SuppressWarnings("unchecked")
        var iterable = mock(PagedIterable.class);
        when(iterable.iterator()).thenReturn(items.iterator());
        when(client.listPaths(any(ListPathsOptions.class), isNull()))
                .thenReturn(iterable);

        var listing =
                provider.newDirectoryStream(fs.getPath("/root"), p -> true);
        var children = new ArrayList<Path>();
        for (Path child : listing) {
            children.add(child);
        }

        assertThat(children).extracting(Path::toString)
                .containsExactlyInAnyOrder("/root/file.txt", "/root/folder");
        assertThat(provider.readAttributes(children.getFirst(),
                java.nio.file.attribute.BasicFileAttributes.class).size())
                        .isEqualTo(7L);
    }

    @Test
    void testInputStreamByteChannelAndAclCaching() throws Exception {
        var provider = new AdlsGen2FileSystemProvider();
        var location = AdlsGen2Location.from(URI.create(
                "abfss://myfs@acct.dfs.core.windows.net/root"));
        var client = mock(DataLakeFileSystemClient.class);
        var fileClient = mock(DataLakeFileClient.class);
        var streamResult = mock(DataLakeFileOpenInputStreamResult.class);
        var props = mock(PathProperties.class);
        var ace = mock(PathAccessControlEntry.class);
        var fs = provider.getOrCreateFileSystem(location, loc -> client);
        var path = fs.getPath("/folder/file.txt");
        var now = OffsetDateTime.now();

        when(client.getFileClient("folder/file.txt")).thenReturn(fileClient);
        when(fileClient.openInputStream()).thenReturn(streamResult);
        when(streamResult.getInputStream())
                .thenAnswer(invocation -> new ByteArrayInputStream(
                        "abc".getBytes(UTF_8)));
        when(fileClient.exists()).thenReturn(true);
        when(fileClient.getProperties()).thenReturn(props);
        when(props.getFileSize()).thenReturn(3L);
        when(props.getLastModified()).thenReturn(now);
        when(props.getOwner()).thenReturn("owner");
        when(props.getGroup()).thenReturn("group");
        when(props.getPermissions()).thenReturn("rwx------");
        when(props.getAccessControlList()).thenReturn(
                java.util.Arrays.asList(null, ace));
        when(ace.isInDefaultScope()).thenReturn(true);
        when(ace.getAccessControlType()).thenReturn(null);
        when(ace.getEntityId()).thenReturn("user1");
        when(ace.getPermissions()).thenReturn(null);

        try (var is = provider.newInputStream(path)) {
            assertThat(is.readAllBytes())
                    .containsExactly("abc".getBytes(UTF_8));
        }

        try (var channel = provider.newByteChannel(path, Set.of())) {
            var dst = ByteBuffer.allocate(8);
            assertThat(channel.read(dst)).isEqualTo(3);
            assertThat(channel.position()).isEqualTo(3L);
            assertThat(channel.size()).isEqualTo(3L);
            assertThat(channel.isOpen()).isTrue();
            assertThat(channel.position(1).position()).isEqualTo(1L);
            assertThat(channel.read(ByteBuffer.allocate(8))).isEqualTo(2);
            assertThat(channel.read(ByteBuffer.allocate(8))).isEqualTo(-1);
            assertThatThrownBy(() -> channel.write(ByteBuffer.wrap(
                    "x".getBytes(UTF_8))))
                            .isInstanceOf(
                                    NonWritableChannelException.class);
            assertThatThrownBy(() -> channel.truncate(1))
                    .isInstanceOf(NonWritableChannelException.class);
            channel.close();
            assertThat(channel.isOpen()).isFalse();
        }

        var attrs = provider.readAttributes(path, BasicFileAttributes.class);
        assertThat(attrs.isRegularFile()).isTrue();
        assertThat(attrs.size()).isEqualTo(3L);

        var aclProps = provider.consumeAcl(((AdlsGen2Path) path).path());
        assertThat(aclProps).isNotNull();
        assertThat(aclProps.getString(FsDocMetadata.ACL + ".owner"))
                .isEqualTo("owner");
        assertThat(aclProps.getString(FsDocMetadata.ACL + ".group"))
                .isEqualTo("group");
        assertThat(aclProps.getString(FsDocMetadata.ACL + ".permissions"))
                .isEqualTo("rwx------");
        assertThat(provider.consumeAcl(((AdlsGen2Path) path).path())).isNull();
    }

    @Test
    void testProviderErrorBranches() throws Exception {
        var provider = new AdlsGen2FileSystemProvider();
        var location = AdlsGen2Location.from(URI.create(
                "abfss://myfs@acct.dfs.core.windows.net/root"));
        var client = mock(DataLakeFileSystemClient.class);
        var fileClient = mock(DataLakeFileClient.class);
        var dirClient = mock(DataLakeDirectoryClient.class);
        var missing = mock(DataLakeStorageException.class);
        var fs = provider.getOrCreateFileSystem(location, loc -> client);
        var missingPath = fs.getPath("/missing.txt");

        when(missing.getStatusCode()).thenReturn(404);
        when(client.getFileClient("missing.txt")).thenReturn(fileClient);
        when(fileClient.openInputStream()).thenThrow(missing);
        when(client.listPaths(any(ListPathsOptions.class), isNull()))
                .thenThrow(missing);
        when(client.getDirectoryClient("missing.txt")).thenReturn(dirClient);
        when(fileClient.exists()).thenReturn(false);
        when(dirClient.exists()).thenReturn(false);

        assertThatThrownBy(() -> provider.newInputStream(missingPath))
                .isInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> provider.newDirectoryStream(
                fs.getPath("/missing"), p -> true))
                        .isInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> provider.readAttributes(missingPath,
                BasicFileAttributes.class))
                        .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void testProviderDirectoryAndIoExceptionBranches() throws Exception {
        var provider = new AdlsGen2FileSystemProvider();
        var location = AdlsGen2Location.from(URI.create(
                "abfss://myfs@acct.dfs.core.windows.net/root"));
        var client = mock(DataLakeFileSystemClient.class);
        var fileClient = mock(DataLakeFileClient.class);
        var dirClient = mock(DataLakeDirectoryClient.class);
        var badStatus = mock(DataLakeStorageException.class);
        var dirProps = mock(PathProperties.class);
        var fs = provider.getOrCreateFileSystem(location, loc -> client);
        var dirPath = fs.getPath("/folder");
        var filePath = fs.getPath("/broken.txt");
        var now = OffsetDateTime.now();

        when(client.getFileClient("folder")).thenReturn(fileClient);
        when(client.getDirectoryClient("folder")).thenReturn(dirClient);
        when(fileClient.exists()).thenReturn(false);
        when(dirClient.exists()).thenReturn(true);
        when(dirClient.getProperties()).thenReturn(dirProps);
        when(dirProps.getLastModified()).thenReturn(now);
        when(dirProps.getAccessControlList()).thenReturn(null);

        var dirAttrs =
                provider.readAttributes(dirPath, BasicFileAttributes.class);
        assertThat(dirAttrs.isDirectory()).isTrue();
        assertThat(dirAttrs.lastModifiedTime())
                .isEqualTo(FileTime.from(now.toInstant()));

        when(client.getFileClient("broken.txt")).thenReturn(fileClient);
        when(fileClient.openInputStream()).thenThrow(badStatus);
        when(badStatus.getStatusCode()).thenReturn(500);
        assertThatThrownBy(() -> provider.newInputStream(filePath))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Could not read ADLS Gen2 file");

        @SuppressWarnings("unchecked")
        var iterable = mock(PagedIterable.class);
        when(iterable.iterator()).thenReturn(
                java.util.Arrays.<PathItem>asList(
                        null,
                        new PathItem("etag", null, 0L,
                                null, false, "", null, null),
                        new PathItem("etag2", null, 5L,
                                null, false, "root/ok.txt", null, null))
                        .iterator());
        when(client.listPaths(any(ListPathsOptions.class), isNull()))
                .thenReturn(iterable);

        var filtered = new ArrayList<Path>();
        try (var stream = provider.newDirectoryStream(fs.getPath("/root"),
                p -> false)) {
            for (Path child : stream) {
                filtered.add(child);
            }
        }
        assertThat(filtered).isEmpty();

        when(client.listPaths(any(ListPathsOptions.class), isNull()))
                .thenThrow(badStatus);
        assertThatThrownBy(
                () -> provider.newDirectoryStream(fs.getPath("/root"),
                        p -> true))
                                .isInstanceOf(java.io.IOException.class)
                                .hasMessageContaining(
                                        "Could not list ADLS Gen2 paths under");

        when(fileClient.exists()).thenThrow(badStatus);
        assertThatThrownBy(() -> provider.readAttributes(filePath,
                BasicFileAttributes.class))
                        .isInstanceOf(java.io.IOException.class)
                        .hasMessageContaining(
                                "Could not read ADLS Gen2 path attributes");
    }

    @Test
    void testProviderRootAttributesAndUnsupportedOperations() throws Exception {
        var provider = new AdlsGen2FileSystemProvider();
        var location = AdlsGen2Location.from(URI.create(
                "abfss://myfs@acct.dfs.core.windows.net/root"));
        var client = mock(DataLakeFileSystemClient.class);
        var fs = provider.getOrCreateFileSystem(location, loc -> client);
        var root = fs.getPath("/");

        var basicView = provider.getFileAttributeView(root,
                BasicFileAttributeView.class);
        assertThat(basicView).isNotNull();
        assertThat(basicView.name()).isEqualTo("basic");
        assertThat(basicView.readAttributes().isDirectory()).isTrue();
        assertThat(provider.getFileAttributeView(root,
                java.nio.file.attribute.FileAttributeView.class)).isNull();

        assertThat(provider.readAttributes(root,
                java.nio.file.attribute.BasicFileAttributes.class)
                .isDirectory()).isTrue();
        assertThatCode(() -> provider.checkAccess(root))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> provider.readAttributes(root,
                java.nio.file.attribute.PosixFileAttributes.class))
                        .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.readAttributes(root, "basic:*"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> basicView.setTimes(FileTime.fromMillis(0),
                FileTime.fromMillis(0), FileTime.fromMillis(0)))
                        .isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> provider.checkAccess(root, AccessMode.WRITE))
                .isInstanceOf(java.nio.file.AccessDeniedException.class);
        assertThat(provider.isSameFile(root, fs.getPath("/"))).isTrue();
        assertThat(provider.isHidden(root)).isFalse();
        assertThatThrownBy(() -> provider.getFileStore(root))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.createDirectory(root))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.delete(root))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.copy(root, root))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.move(root, root))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.setAttribute(root, "x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
