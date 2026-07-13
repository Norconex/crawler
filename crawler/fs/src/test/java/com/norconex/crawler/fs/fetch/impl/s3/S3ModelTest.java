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
package com.norconex.crawler.fs.fetch.impl.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

class S3ModelTest {

    private S3FileSystemProvider provider;
    private S3Client client;
    private S3FileSystem fs;

    @BeforeEach
    void setup() {
        provider = new S3FileSystemProvider();
        client = mock(S3Client.class);
        fs = provider.getOrCreateFileSystem(
                URI.create("s3://bucket/"), b -> client);
    }

    @Test
    void testFileAttributesDefaultsAndFlags() {
        var attrs = new S3FileAttributes(true, -3, null);

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
    void testFileSystemAndPathOperations() {
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

        var p = (S3Path) child;
        assertThat(p.path()).isEqualTo("/a/b/c.txt");
        assertThat(p.key()).isEqualTo("a/b/c.txt");
        assertThat(p.keyAsPrefix()).isEqualTo("a/b/c.txt/");
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
        assertThat(p.toUri().toString()).isEqualTo("s3://bucket/a/b/c.txt");
        assertThat(p.toAbsolutePath()).isSameAs(p);
        assertThat(p.toRealPath()).isSameAs(p);
        assertThat((Iterable<Path>) p).hasSize(3);
        assertThat(p.compareTo(sibling)).isNegative();
        assertThat(p).isEqualTo(fs.getPath("/a/b/c.txt"));

        assertThatThrownBy(p::toFile)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> p.resolve(Path.of("x")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(fs.provider()).isSameAs(provider);
        assertThatThrownBy(fs::getUserPrincipalLookupService)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(fs::newWatchService)
                .isInstanceOf(UnsupportedOperationException.class);

        fs.close();
        assertThat(fs.isOpen()).isFalse();
        verify(client).close();
    }

    @Test
    void testProviderLifecycleAndLookup() {
        assertThat(provider.openFileSystems()).hasSize(1);
        assertThat(provider.getScheme()).isEqualTo("s3");
        assertThat(provider.getFileSystem(URI.create("s3://bucket/a")))
                .isSameAs(fs);
        assertThat(provider.getPath(URI.create("s3://bucket/a/b")).toString())
                .isEqualTo("/a/b");

        assertThatThrownBy(() -> provider.newFileSystem(
                URI.create("s3://bucket/"), Map.of()))
                        .isInstanceOf(UnsupportedOperationException.class)
                        .hasMessageContaining("Use getOrCreateFileSystem");

        assertThatThrownBy(() -> provider.getFileSystem(
                URI.create("s3://missing/a")))
                        .isInstanceOf(FileSystemNotFoundException.class);
        assertThatThrownBy(() -> provider.getPath(URI.create("s3://missing/a")))
                .isInstanceOf(FileSystemNotFoundException.class);
    }

    @Test
    void testProviderReadOnlyOperationsAndAccessChecks() throws Exception {
        var root = fs.getPath("/");

        assertThat(provider.isSameFile(root, fs.getPath("/"))).isTrue();
        assertThat(provider.isHidden(root)).isFalse();

        assertThatCode(() -> provider.checkAccess(root))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> provider.checkAccess(root,
                java.nio.file.AccessMode.WRITE))
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
                "basic:lastModifiedTime", FileTime.fromMillis(0)))
                        .isInstanceOf(UnsupportedOperationException.class)
                        .hasMessageContaining("read-only");
        assertThatThrownBy(() -> provider.readAttributes(root, "basic:*"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testInputStreamByteChannelAndReadAttributes() throws Exception {
        var path = fs.getPath("/a/b/c.txt");
        var in = mock(ResponseInputStream.class);

        when(client.getObject(any(
                software.amazon.awssdk.services.s3.model.GetObjectRequest.class)))
                        .thenReturn(in);
        when(in.readAllBytes()).thenReturn("abc".getBytes());
        when(client.headObject(any(
                software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                        .thenReturn(HeadObjectResponse.builder()
                                .contentLength(3L)
                                .lastModified(Instant.now())
                                .build());

        try (var is = provider.newInputStream(path)) {
            assertThat(is.readAllBytes()).containsExactly("abc".getBytes());
        }

        try (var channel = provider.newByteChannel(path, Set.of())) {
            var dst = ByteBuffer.allocate(8);
            assertThat(channel.read(dst)).isEqualTo(3);
            assertThat(channel.position()).isEqualTo(3L);
            assertThat(channel.size()).isEqualTo(3L);
            assertThatThrownBy(
                    () -> channel.write(ByteBuffer.wrap("x".getBytes())))
                            .isInstanceOf(NonWritableChannelException.class);
            assertThatThrownBy(() -> channel.truncate(1))
                    .isInstanceOf(NonWritableChannelException.class);
        }

        var attrs = provider.readAttributes(path, BasicFileAttributes.class);
        assertThat(attrs.isRegularFile()).isTrue();
        assertThat(attrs.isDirectory()).isFalse();
        assertThat(attrs.size()).isEqualTo(3L);
    }

    @Test
    void testNotFoundAndVirtualDirectoryPaths() throws Exception {
        var missingFile = fs.getPath("/missing.txt");
        var notFound = S3Exception.builder().statusCode(404).build();
        when(client.getObject(any(
                software.amazon.awssdk.services.s3.model.GetObjectRequest.class)))
                        .thenThrow(notFound);

        assertThatThrownBy(() -> provider.newInputStream(missingFile))
                .isInstanceOf(NoSuchFileException.class);

        var virtualDir = fs.getPath("/vdir");
        when(client.headObject(any(
                software.amazon.awssdk.services.s3.model.HeadObjectRequest.class)))
                        .thenThrow(notFound);
        when(client.listObjectsV2(any(
                software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class)))
                        .thenReturn(ListObjectsV2Response.builder()
                                .commonPrefixes(CommonPrefix.builder()
                                        .prefix("vdir/sub/").build())
                                .build());

        var dirAttrs = provider.readAttributes(virtualDir,
                BasicFileAttributes.class);
        assertThat(dirAttrs.isDirectory()).isTrue();
    }

    @Test
    void testDirectoryStreamAndAttributeCache() throws Exception {
        var paginator = mock(ListObjectsV2Iterable.class);
        var page = ListObjectsV2Response.builder()
                .contents(
                        S3Object.builder().key("dir/").build(),
                        S3Object.builder().key("dir/file.txt").size(7L)
                                .lastModified(Instant.now())
                                .build())
                .commonPrefixes(
                        CommonPrefix.builder().prefix("dir/sub/").build())
                .build();
        when(paginator.iterator()).thenReturn(List.of(page).iterator());
        when(client.listObjectsV2Paginator(any(
                software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class)))
                        .thenReturn(paginator);

        var children = new java.util.ArrayList<Path>();
        try (var stream =
                provider.newDirectoryStream(fs.getPath("/dir"), p -> true)) {
            for (Path p : stream) {
                children.add(p);
            }
        }

        assertThat(children).extracting(Path::toString)
                .contains("/dir/file.txt", "/dir/sub");
        assertThat(provider.readAttributes(fs.getPath("/dir/sub"),
                BasicFileAttributes.class).isDirectory())
                        .isTrue();
        assertThat(provider.readAttributes(fs.getPath("/dir/file.txt"),
                BasicFileAttributes.class).size())
                        .isEqualTo(7L);
    }

    @Test
    void testGetFileAttributeViewAndUnsupportedType() throws Exception {
        var root = fs.getPath("/");
        var view = provider.getFileAttributeView(root,
                java.nio.file.attribute.BasicFileAttributeView.class);
        assertThat(view).isNotNull();
        assertThat(view.name()).isEqualTo("basic");
        assertThat(view.readAttributes().isDirectory()).isTrue();
        assertThatThrownBy(() -> view.setTimes(null, null, null))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(provider.getFileAttributeView(root,
                java.nio.file.attribute.FileOwnerAttributeView.class))
                        .isNull();
        assertThatThrownBy(() -> provider.readAttributes(root,
                java.nio.file.attribute.PosixFileAttributes.class))
                        .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testNon404ErrorsAreWrapped() {
        var notPermitted = S3Exception.builder().statusCode(403).build();
        when(client.getObject(any(
                software.amazon.awssdk.services.s3.model.GetObjectRequest.class)))
                        .thenThrow(notPermitted);

        assertThatThrownBy(() -> provider.newInputStream(fs.getPath("/x")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Could not read S3 object");

        when(client.listObjectsV2Paginator(any(
                software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class)))
                        .thenThrow(notPermitted);

        assertThatThrownBy(
                () -> provider.newDirectoryStream(fs.getPath("/x"), p -> true))
                        .isInstanceOf(java.io.IOException.class)
                        .hasMessageContaining(
                                "Could not list S3 objects under");
    }
