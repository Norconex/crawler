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
package com.norconex.crawler.fs.fetch.impl.azureblob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.azure.core.http.rest.PagedIterable;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobItemProperties;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.specialized.BlobInputStream;

class AzureBlobModelTest {

    private AzureBlobFileSystemProvider provider;
    private BlobContainerClient containerClient;
    private AzureBlobLocation location;
    private AzureBlobFileSystem fs;

    @BeforeEach
    void setup() {
        provider = new AzureBlobFileSystemProvider();
        containerClient = mock(BlobContainerClient.class);
        location = new AzureBlobLocation("azblob", "acct", "container",
                "/");
        fs = provider.getOrCreateFileSystem(location,
                l -> containerClient);
    }

    @Test
    void testFileAttributesDefaultsAndFlags() {
        var attrs = new AzureBlobFileAttributes(false, -5, null);

        assertThat(attrs.isRegularFile()).isTrue();
        assertThat(attrs.isDirectory()).isFalse();
        assertThat(attrs.isSymbolicLink()).isFalse();
        assertThat(attrs.isOther()).isFalse();
        assertThat(attrs.size()).isZero();
        assertThat(attrs.lastModifiedTime())
                .isEqualTo(FileTime.fromMillis(0));
        assertThat(attrs.lastAccessTime())
                .isEqualTo(FileTime.fromMillis(0));
        assertThat(attrs.creationTime())
                .isEqualTo(FileTime.fromMillis(0));
        assertThat(attrs.fileKey()).isNull();
    }

    @Test
    void testFileSystemAndPathOperations() {
        var root = fs.getPath("/");
        var child = fs.getPath("/folder/sub/file.txt");
        var sibling = fs.getPath("/folder/sub/other.txt");

        assertThat(fs.isOpen()).isTrue();
        assertThat(fs.isReadOnly()).isTrue();
        assertThat(fs.getSeparator()).isEqualTo("/");
        assertThat(fs.supportedFileAttributeViews())
                .containsExactly("basic");
        assertThat(fs.getRootDirectories()).containsExactly(root);
        assertThat(fs.getFileStores()).isEmpty();

        var glob = fs.getPathMatcher("glob:**/*.txt");
        assertThat(glob.matches(child)).isTrue();
        assertThatThrownBy(() -> fs.getPathMatcher("regex:.*"))
                .isInstanceOf(UnsupportedOperationException.class);

        var ap = (AzureBlobPath) child;
        assertThat(ap.path()).isEqualTo("/folder/sub/file.txt");
        assertThat(ap.blobName()).isEqualTo("folder/sub/file.txt");
        assertThat(ap.blobPrefix()).isEqualTo("folder/sub/file.txt/");
        assertThat(ap.getFileSystem()).isSameAs(fs);
        assertThat(ap.isAbsolute()).isTrue();
        assertThat(ap.getRoot()).isEqualTo(root);
        assertThat(ap.getFileName().toString()).isEqualTo("/file.txt");
        assertThat(ap.getParent().toString()).isEqualTo("/folder/sub");
        assertThat(ap.getNameCount()).isEqualTo(3);
        assertThat(ap.getName(0).toString()).isEqualTo("/folder");
        assertThat(ap.subpath(1, 3).toString())
                .isEqualTo("/sub/file.txt");
        assertThat(ap.startsWith(fs.getPath("/folder"))).isTrue();
        assertThat(ap.startsWith("/folder")).isTrue();
        assertThat(ap.endsWith(fs.getPath("/file.txt"))).isTrue();
        assertThat(ap.endsWith("/file.txt")).isTrue();
        assertThat(ap.normalize()).isSameAs(ap);
        assertThat(ap.resolveSibling(sibling)).isEqualTo(sibling);
        assertThat(ap.resolveSibling("/x/y/z.txt").toString())
                .isEqualTo("/x/y/z.txt");
        assertThat(ap.relativize(sibling).toString())
                .isEqualTo("/../other.txt");
        assertThat(ap.toUri().toString())
                .isEqualTo("azblob://acct/container/folder/sub/file.txt");
        assertThat(ap.toAbsolutePath()).isSameAs(ap);
        assertThat(ap.toRealPath()).isSameAs(ap);
        assertThat((Iterable<Path>) ap).hasSize(3);
        assertThat(ap.compareTo(sibling)).isNegative();
        assertThat(ap).isEqualTo(fs.getPath("/folder/sub/file.txt"));
        assertThat(ap.hashCode())
                .isEqualTo(fs.getPath("/folder/sub/file.txt")
                        .hashCode());

        assertThatThrownBy(ap::toFile)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ap.resolve(Path.of("x")))
                .isInstanceOf(IllegalArgumentException.class);

        fs.close();
        assertThat(fs.isOpen()).isFalse();
    }

    @Test
    void testProviderLifecycleAndLookup() {
        assertThat(provider.getScheme()).isEqualTo("azblob");
        assertThat(provider.openFileSystems()).hasSize(1);
        assertThat(provider.getFileSystem(
                URI.create("azblob://acct/container/root.txt")))
                        .isSameAs(fs);
        assertThat(provider
                .getPath(URI.create(
                        "azblob://acct/container/a/b"))
                .toString())
                        .isEqualTo("/a/b");

        assertThatThrownBy(() -> provider.newFileSystem(
                URI.create("azblob://acct/container"),
                Map.of()))
                        .isInstanceOf(UnsupportedOperationException.class)
                        .hasMessageContaining(
                                "Use getOrCreateFileSystem");

        var missing = URI.create("azblob://missing/container/a");
        assertThatThrownBy(() -> provider.getFileSystem(missing))
                .isInstanceOf(FileSystemNotFoundException.class);
        assertThatThrownBy(() -> provider.getPath(missing))
                .isInstanceOf(FileSystemNotFoundException.class);
    }

    @Test
    void testProviderReadOnlyOperationsAndAccessChecks() throws Exception {
        var root = fs.getPath("/");

        assertThat(provider.isSameFile(root, fs.getPath("/"))).isTrue();
        assertThat(provider.isHidden(root)).isFalse();

        assertThatCode(() -> provider.checkAccess(root))
                .doesNotThrowAnyException();
        assertThatCode(() -> provider.checkAccess(root,
                java.nio.file.AccessMode.READ))
                        .doesNotThrowAnyException();
        assertThatThrownBy(() -> provider.checkAccess(root,
                java.nio.file.AccessMode.WRITE))
                        .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> provider.checkAccess(root,
                java.nio.file.AccessMode.EXECUTE))
                        .isInstanceOf(AccessDeniedException.class);

        assertThatThrownBy(() -> provider.getFileStore(root))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.createDirectory(root))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> provider.delete(root))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> provider.copy(root, root))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> provider.move(root, root))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> provider.setAttribute(root,
                "basic:lastModifiedTime",
                FileTime.fromMillis(0)))
                        .isInstanceOf(UnsupportedOperationException.class)
                        .hasMessageContaining(
                                "read-only");
        assertThatThrownBy(
                () -> provider.readAttributes(root, "basic:*"))
                        .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testInputStreamByteChannelAndFileAttributes() throws Exception {
        var path = fs.getPath("/folder/file.txt");
        var blobClient = mock(BlobClient.class);
        var blobInput = mock(BlobInputStream.class);
        var blobProps = mock(BlobProperties.class);

        when(containerClient.getBlobClient("folder/file.txt"))
                .thenReturn(blobClient);
        when(blobClient.openInputStream()).thenReturn(blobInput);
        when(blobInput.readAllBytes()).thenReturn("abc".getBytes());
        when(blobClient.getProperties()).thenReturn(blobProps);
        when(blobProps.getBlobSize()).thenReturn(3L);
        when(blobProps.getLastModified())
                .thenReturn(OffsetDateTime.now());

        try (var is = provider.newInputStream(path)) {
            assertThat(is.readAllBytes())
                    .containsExactly("abc".getBytes());
        }

        try (var channel = provider.newByteChannel(path, Set.of())) {
            var dst = ByteBuffer.allocate(8);
            assertThat(channel.read(dst)).isEqualTo(3);
            assertThat(channel.read(ByteBuffer.allocate(1))).isEqualTo(-1);
            assertThat(channel.position()).isEqualTo(3L);
            channel.position(1);
            assertThat(channel.position()).isEqualTo(1L);
            assertThat(channel.size()).isEqualTo(3L);
            assertThat(channel.isOpen()).isTrue();
            assertThatThrownBy(
                    () -> channel.write(ByteBuffer
                            .wrap("x".getBytes())))
                                    .isInstanceOf(
                                            NonWritableChannelException.class);
            assertThatThrownBy(() -> channel.truncate(1))
                    .isInstanceOf(NonWritableChannelException.class);
        }

        var attrs = provider.readAttributes(path,
                BasicFileAttributes.class);
        assertThat(attrs.isRegularFile()).isTrue();
        assertThat(attrs.isDirectory()).isFalse();
        assertThat(attrs.size()).isEqualTo(3L);

        var view = provider.getFileAttributeView(path,
                BasicFileAttributeView.class);
        assertThat(view).isNotNull();
        assertThat(view.name()).isEqualTo("basic");
        assertThat(view.readAttributes().size()).isEqualTo(3L);
        assertThatThrownBy(() -> view.setTimes(null, null, null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(provider.getFileAttributeView(path,
                FileAttributeView.class)).isNull();
    }

    @Test
    void testInputStreamErrorsAndDirectoryListingCache() throws Exception {
        var notFoundPath = fs.getPath("/missing.txt");
        var failingBlobClient = mock(BlobClient.class);
        var notFound = mock(BlobStorageException.class);
        when(notFound.getStatusCode()).thenReturn(404);

        when(containerClient.getBlobClient("missing.txt"))
                .thenReturn(failingBlobClient);
        when(failingBlobClient.openInputStream()).thenThrow(notFound);

        assertThatThrownBy(() -> provider.newInputStream(notFoundPath))
                .isInstanceOf(NoSuchFileException.class);

        var listing = mock(PagedIterable.class);
        var dirItem = mock(BlobItem.class);
        when(dirItem.isPrefix()).thenReturn(true);
        when(dirItem.getName()).thenReturn("folder/");

        var fileItem = mock(BlobItem.class);
        var fileItemProps = mock(BlobItemProperties.class);
        when(fileItem.isPrefix()).thenReturn(false);
        when(fileItem.getName()).thenReturn("root.txt");
        when(fileItem.getProperties()).thenReturn(fileItemProps);
        when(fileItemProps.getContentLength()).thenReturn(7L);
        when(fileItemProps.getLastModified())
                .thenReturn(OffsetDateTime.now());

        when(listing.iterator())
                .thenReturn(List.of(dirItem, fileItem)
                        .iterator());
        when(containerClient.listBlobsByHierarchy(eq("/"),
                any(ListBlobsOptions.class), isNull()))
                        .thenReturn(listing);

        var children = new java.util.ArrayList<Path>();
        try (var stream =
                provider.newDirectoryStream(fs.getPath("/"),
                        p -> true)) {
            for (Path child : stream) {
                children.add(child);
            }
        }

        assertThat(children).extracting(Path::toString)
                .contains("/folder", "/root.txt");
        assertThat(provider.readAttributes(fs.getPath("/folder"),
                BasicFileAttributes.class).isDirectory())
                        .isTrue();

        var listNotFound = mock(BlobStorageException.class);
        when(listNotFound.getStatusCode()).thenReturn(404);
        when(containerClient.listBlobsByHierarchy(eq("/"),
                any(ListBlobsOptions.class), isNull()))
                        .thenThrow(listNotFound);
        assertThatThrownBy(() -> provider.newDirectoryStream(
                fs.getPath("/"), p -> true))
                        .isInstanceOf(NoSuchFileException.class);

        var listOther = mock(BlobStorageException.class);
        when(listOther.getStatusCode()).thenReturn(500);
        when(containerClient.listBlobsByHierarchy(eq("/"),
                any(ListBlobsOptions.class), isNull()))
                        .thenThrow(listOther);
        assertThatThrownBy(() -> provider.newDirectoryStream(
                fs.getPath("/"), p -> true))
                        .isInstanceOf(java.io.IOException.class)
                        .hasMessageContaining("Could not list Azure blobs");
    }

    @Test
    void testDirectoryListingBranchCasesAndReadAttributesErrorPaths()
            throws Exception {
        var dirPath = fs.getPath("/dir");
        var listing = mock(PagedIterable.class);

        var blankPrefix = mock(BlobItem.class);
        when(blankPrefix.isPrefix()).thenReturn(true);
        when(blankPrefix.getName()).thenReturn("/");

        var selfFile = mock(BlobItem.class);
        when(selfFile.isPrefix()).thenReturn(false);
        when(selfFile.getName()).thenReturn("dir/");

        var nullPropFile = mock(BlobItem.class);
        when(nullPropFile.isPrefix()).thenReturn(false);
        when(nullPropFile.getName()).thenReturn("dir/a.txt");
        when(nullPropFile.getProperties()).thenReturn(null);

        var nullLenFile = mock(BlobItem.class);
        var nullLenProps = mock(BlobItemProperties.class);
        when(nullLenFile.isPrefix()).thenReturn(false);
        when(nullLenFile.getName()).thenReturn("dir/b.txt");
        when(nullLenFile.getProperties()).thenReturn(nullLenProps);
        when(nullLenProps.getContentLength()).thenReturn(null);
        when(nullLenProps.getLastModified()).thenReturn(null);

        when(listing.iterator()).thenReturn(
                List.of(blankPrefix, selfFile, nullPropFile, nullLenFile)
                        .iterator());
        when(containerClient.listBlobsByHierarchy(eq("/"),
                any(ListBlobsOptions.class), isNull()))
                        .thenReturn(listing);

        try (var stream = provider.newDirectoryStream(dirPath, p -> false)) {
            assertThat(stream.iterator().hasNext()).isFalse();
        }

        assertThat(provider.readAttributes(fs.getPath("/dir/a.txt"),
                BasicFileAttributes.class).size()).isZero();
        assertThat(provider.readAttributes(fs.getPath("/dir/b.txt"),
                BasicFileAttributes.class).lastModifiedTime())
                        .isEqualTo(FileTime.fromMillis(0));

        assertThatThrownBy(() -> provider.readAttributes(
                fs.getPath("/x.txt"),
                java.nio.file.attribute.PosixFileAttributes.class))
                        .isInstanceOf(UnsupportedOperationException.class);

        var err500Blob = mock(BlobClient.class);
        var err500 = mock(BlobStorageException.class);
        when(err500.getStatusCode()).thenReturn(500);
        when(containerClient.getBlobClient("err500.txt"))
                .thenReturn(err500Blob);
        when(err500Blob.getProperties()).thenThrow(err500);
        assertThatThrownBy(() -> provider.readAttributes(
                fs.getPath("/err500.txt"), BasicFileAttributes.class))
                        .isInstanceOf(java.io.IOException.class)
                        .hasMessageContaining(
                                "Could not read Azure blob attributes");

        var nfBlob = mock(BlobClient.class);
        var nf = mock(BlobStorageException.class);
        when(nf.getStatusCode()).thenReturn(404);
        when(containerClient.getBlobClient("vdir/ghost.txt"))
                .thenReturn(nfBlob);
        when(nfBlob.getProperties()).thenThrow(nf);
        var vdirPaged = mockPagedOf(mock(BlobItem.class));
        when(containerClient.listBlobsByHierarchy(eq("/"),
                any(ListBlobsOptions.class), isNull()))
                        .thenReturn(vdirPaged);
        assertThat(provider.readAttributes(
                fs.getPath("/vdir/ghost.txt"), BasicFileAttributes.class)
                .isDirectory()).isTrue();

        var missingBlob = mock(BlobClient.class);
        when(containerClient.getBlobClient("missing/ghost.txt"))
                .thenReturn(missingBlob);
        when(missingBlob.getProperties()).thenThrow(nf);
        var missingPaged = mockPagedOf();
        when(containerClient.listBlobsByHierarchy(eq("/"),
                any(ListBlobsOptions.class), isNull()))
                        .thenReturn(missingPaged);
        assertThatThrownBy(() -> provider.readAttributes(
                fs.getPath("/missing/ghost.txt"), BasicFileAttributes.class))
                        .isInstanceOf(NoSuchFileException.class);

        var failListBlob = mock(BlobClient.class);
        var listErr = mock(BlobStorageException.class);
        when(listErr.getStatusCode()).thenReturn(500);
        when(containerClient.getBlobClient("broken/ghost.txt"))
                .thenReturn(failListBlob);
        when(failListBlob.getProperties()).thenThrow(nf);
        when(containerClient.listBlobsByHierarchy(eq("/"),
                any(ListBlobsOptions.class), isNull()))
                        .thenThrow(listErr);
        assertThatThrownBy(() -> provider.readAttributes(
                fs.getPath("/broken/ghost.txt"), BasicFileAttributes.class))
                        .isInstanceOf(java.io.IOException.class)
                        .hasMessageContaining(
                                "Could not check Azure virtual directory");

        var ioBlobClient = mock(BlobClient.class);
        when(containerClient.getBlobClient("iofail.txt"))
                .thenReturn(ioBlobClient);
        when(ioBlobClient.openInputStream()).thenThrow(err500);
        assertThatThrownBy(
                () -> provider.newInputStream(fs.getPath("/iofail.txt")))
                        .isInstanceOf(java.io.IOException.class)
                        .hasMessageContaining("Could not read Azure blob");
    }

    @SafeVarargs
    private static PagedIterable<BlobItem> mockPagedOf(BlobItem... items) {
        @SuppressWarnings("unchecked")
        var paged = mock(PagedIterable.class);
        when(paged.iterator()).thenReturn(Arrays.asList(items).iterator());
        return paged;
    }
}
