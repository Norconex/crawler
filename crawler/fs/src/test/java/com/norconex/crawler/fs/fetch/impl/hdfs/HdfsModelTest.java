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
package com.norconex.crawler.fs.fetch.impl.hdfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.AccessMode;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.util.Map;

import javax.security.auth.Subject;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HdfsModelTest {

    @Test
    void testFileAttributesDefaultsAndFlags() {
        var attrs = new HdfsFileAttributes(true, -4, 1200L, 1300L);

        assertThat(attrs.isDirectory()).isTrue();
        assertThat(attrs.isRegularFile()).isFalse();
        assertThat(attrs.isSymbolicLink()).isFalse();
        assertThat(attrs.isOther()).isFalse();
        assertThat(attrs.size()).isZero();
        assertThat(attrs.lastModifiedTime())
                .isEqualTo(FileTime.fromMillis(1200));
        assertThat(attrs.lastAccessTime()).isEqualTo(FileTime.fromMillis(1300));
        assertThat(attrs.creationTime()).isEqualTo(FileTime.fromMillis(1200));
        assertThat(attrs.fileKey()).isNull();
    }

    @Test
    void testFileSystemProviderAndPathOperations() throws Exception {
        var provider = new HdfsFileSystemProvider();
        var client = mock(CloseableHttpClient.class);
        var uri = URI.create("webhdfs://namenode.example.com:9870/root");

        var fs = provider.getOrCreateFileSystem(uri, "alice", null,
                host -> client);
        assertThat(provider.openFileSystems()).hasSize(1);
        assertThat(provider.getFileSystem(uri)).isSameAs(fs);
        assertThat(provider.getPath(uri).toString()).isEqualTo("/root");
        assertThat(fs.username()).isEqualTo("alice");
        assertThat(fs.host()).isEqualTo("namenode.example.com");
        assertThat(fs.port()).isEqualTo(9870);

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

        var p = (HdfsPath) child;
        assertThat(p.path()).isEqualTo("/a/b/c.txt");
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
                .isEqualTo("webhdfs://namenode.example.com:9870/a/b/c.txt");
        assertThat(p.toAbsolutePath()).isSameAs(p);
        assertThat(p.toRealPath()).isSameAs(p);
        assertThat((Iterable<Path>) p).hasSize(3);
        assertThat(p.compareTo(sibling)).isNegative();
        assertThat(p).isEqualTo(fs.getPath("/a/b/c.txt"));

        assertThatThrownBy(p::toFile)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> p.resolve(Path.of("x")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> p.register(null,
                new WatchEvent.Kind<?>[0]))
                        .isInstanceOf(UnsupportedOperationException.class);

        fs.close();
        assertThat(fs.isOpen()).isFalse();
        verify(client).close();
        assertThat(provider.openFileSystems()).isEmpty();
        assertThatThrownBy(() -> provider.getFileSystem(uri))
                .isInstanceOf(FileSystemNotFoundException.class);
    }

    @Test
    void testProviderReadOnlyAndPathEdgeCases(@TempDir Path tempDir)
            throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "hello");
        var sub = Files.createDirectory(tempDir.resolve("sub"));
        Files.writeString(sub.resolve("b.txt"), "world");

        var server = new HdfsTestServer(tempDir);
        server.start();
        try {
            var provider = new HdfsFileSystemProvider();
            var uriNoPort = URI.create("webhdfs://localhost/");
            var uri = URI.create("webhdfs://localhost:" + server.getLocalPort()
                    + "/");
            var fsDefault = provider.getOrCreateFileSystem(
                    uriNoPort, "", null,
                    host -> mock(CloseableHttpClient.class));
            assertThat(provider.getScheme()).isEqualTo("webhdfs");
            assertThat(fsDefault.port()).isEqualTo(9870);

            var fs = provider.getOrCreateFileSystem(
                    uri, "alice", null,
                    host -> org.apache.hc.client5.http.impl.classic.HttpClients
                            .createDefault());
            var file = fs.getPath("/a.txt");
            var folder = fs.getPath("/sub");

            try (var is = provider.newInputStream(file)) {
                assertThat(new String(is.readAllBytes()))
                        .isEqualTo("hello");
            }

            try (var channel =
                    provider.newByteChannel(file, java.util.Set.of())) {
                var dst = ByteBuffer.allocate(8);
                assertThat(channel.read(dst)).isEqualTo(5);
                assertThat(channel.read(ByteBuffer.allocate(1))).isEqualTo(-1);
                assertThat(channel.size()).isEqualTo(5);
                channel.position(1);
                assertThat(channel.position()).isEqualTo(1);
                assertThatThrownBy(() -> channel.write(ByteBuffer.wrap(
                        new byte[] { 1 }))).isInstanceOf(
                                NonWritableChannelException.class);
                assertThatThrownBy(() -> channel.truncate(1))
                        .isInstanceOf(NonWritableChannelException.class);
                assertThat(channel.isOpen()).isTrue();
            }

            try (var stream = provider.newDirectoryStream(folder,
                    p -> p.toString().endsWith(".txt"))) {
                assertThat(stream.iterator()).hasNext();
            }

            var attrs =
                    provider.readAttributes(file, BasicFileAttributes.class);
            assertThat(attrs.isRegularFile()).isTrue();
            assertThat(provider.readAttributes(file, HdfsFileAttributes.class)
                    .size()).isEqualTo(5L);

            assertThatThrownBy(() -> provider.readAttributes(file,
                    java.nio.file.attribute.PosixFileAttributes.class))
                            .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> provider.readAttributes(file, "basic:*"))
                    .isInstanceOf(UnsupportedOperationException.class);

            var view = provider.getFileAttributeView(file,
                    BasicFileAttributeView.class);
            assertThat(view).isNotNull();
            assertThat(view.name()).isEqualTo("basic");
            assertThat(view.readAttributes().size()).isEqualTo(5L);
            assertThatThrownBy(() -> view.setTimes(null, null, null))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(provider.getFileAttributeView(file,
                    FileAttributeView.class)).isNull();

            provider.checkAccess(file);
            provider.checkAccess(file, AccessMode.READ);
            assertThatThrownBy(() -> provider.checkAccess(file,
                    AccessMode.WRITE))
                            .isInstanceOf(
                                    java.nio.file.AccessDeniedException.class);
            assertThat(provider.isSameFile(file, fs.getPath("/a.txt")))
                    .isTrue();
            assertThat(provider.isHidden(file)).isFalse();

            assertThatThrownBy(() -> provider.getFileStore(file))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> provider.createDirectory(file))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> provider.delete(file))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> provider.copy(file, file))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> provider.move(file, file))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> provider.setAttribute(file, "x", "y"))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> provider.newFileSystem(uri, Map.of()))
                    .isInstanceOf(UnsupportedOperationException.class);

            assertThatThrownBy(
                    () -> provider.newInputStream(fs.getPath("/missing")))
                            .isInstanceOf(
                                    java.nio.file.NoSuchFileException.class);
            assertThatThrownBy(() -> provider.newDirectoryStream(
                    fs.getPath("/missing"), p -> true))
                            .isInstanceOf(
                                    java.nio.file.NoSuchFileException.class);
            assertThatThrownBy(() -> provider.readAttributes(
                    fs.getPath("/missing"), BasicFileAttributes.class))
                            .isInstanceOf(
                                    java.nio.file.NoSuchFileException.class);
        } finally {
            server.stop();
        }
    }

    @Test
    void testHdfsPathEdgeCasesAndMatcherFailures() {
        var provider = new HdfsFileSystemProvider();
        var client = mock(CloseableHttpClient.class);
        var uri = URI.create("webhdfs://namenode.example.com/root");
        var fs = provider.getOrCreateFileSystem(uri, "alice", null,
                host -> client);
        var root = fs.getPath("/");
        var nested = fs.getPath("/a/./b/../c.txt");

        assertThat(root.getFileName()).isNull();
        assertThat(root.getParent()).isNull();
        assertThat(nested.toString()).isEqualTo("/a/c.txt");
        assertThat(nested.startsWith("/a")).isTrue();
        assertThat(nested.endsWith("/c.txt")).isTrue();
        assertThat(nested.startsWith("/x")).isFalse();
        assertThat(nested.endsWith("/x.txt")).isFalse();
        assertThat(nested.relativize(fs.getPath("/a/d/e.txt")).toString())
                .isEqualTo("/../d/e.txt");

        assertThatThrownBy(() -> nested.relativize(Path.of("x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fs.getPathMatcher("regex:.*"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> fs.getPathMatcher("invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(fs::getUserPrincipalLookupService)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(fs::newWatchService)
                .isInstanceOf(UnsupportedOperationException.class);

        var otherFs = provider.getOrCreateFileSystem(
                URI.create("webhdfs://other.example.com/root"), "alice", null,
                host -> mock(CloseableHttpClient.class));
        var samePath = fs.getPath("/a/c.txt");
        var samePathCopy = fs.getPath("/a/c.txt");
        var otherPath = otherFs.getPath("/a/c.txt");

        assertThat(samePath.startsWith(Path.of("/a"))).isFalse();
        assertThat(samePath.endsWith(Path.of("c.txt"))).isFalse();
        assertThat(samePath.startsWith(otherPath)).isFalse();
        assertThat(samePath.endsWith(otherPath)).isFalse();
        assertThat(samePath.equals("x")).isFalse();
        assertThat(samePath.equals(otherPath)).isFalse();
        assertThat(samePath.equals(samePathCopy)).isTrue();
        assertThat(samePath.hashCode()).isEqualTo(samePathCopy.hashCode());
        assertThat(samePath.resolve("tail")).isEqualTo(fs.getPath("/tail"));
        assertThat(root.resolveSibling("peer").toString()).isEqualTo("/peer");
        assertThat(samePath.resolveSibling("z.txt").toString())
                .isEqualTo("/z.txt");
        assertThat(fs.getPath("/a", "b", "c").toString())
                .isEqualTo("/a/b/c");
        assertThat(fs.provider()).isSameAs(provider);
    }

    @Test
    void testHdfsFileSystemCloseIOExceptionPath() throws Exception {
        var provider = new HdfsFileSystemProvider();
        var client = mock(CloseableHttpClient.class);
        var uri = URI.create("webhdfs://namenode.example.com:9870/root");
        var fs = provider.getOrCreateFileSystem(uri, "alice", null,
                host -> client);

        org.mockito.Mockito.doThrow(new java.io.IOException("boom"))
                .when(client).close();

        assertThatThrownBy(fs::close)
                .isInstanceOf(java.io.UncheckedIOException.class)
                .hasCauseInstanceOf(java.io.IOException.class);
        assertThat(fs.isOpen()).isFalse();
        // Second close should be a no-op due to compareAndSet(false).
        fs.close();
    }

    @Test
    void testProviderHttpErrorAndNullEntityBranches() throws Exception {
        var provider = new HdfsFileSystemProvider();
        var client = mock(CloseableHttpClient.class);
        var resp = mock(CloseableHttpResponse.class);
        when(client.execute(any(HttpHost.class), any(HttpGet.class)))
                .thenReturn(resp);

        var fs = provider.getOrCreateFileSystem(
                URI.create("webhdfs://node.example.com:9870/"),
                "alice", null, host -> client);

        when(resp.getCode()).thenReturn(500);
        assertThatThrownBy(() -> provider.newInputStream(fs.getPath("/f.txt")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining(
                        "Unexpected WebHDFS response 500 for OPEN");

        when(resp.getCode()).thenReturn(200);
        when(resp.getEntity()).thenReturn(null);
        try (var is = provider.newInputStream(fs.getPath("/f.txt"))) {
            assertThat(is.readAllBytes()).isEmpty();
        }

        when(resp.getCode()).thenReturn(500);
        assertThatThrownBy(() -> provider.newDirectoryStream(
                fs.getPath("/d"), p -> true))
                        .isInstanceOf(java.io.IOException.class)
                        .hasMessageContaining(
                                "Unexpected WebHDFS response 500 for LISTSTATUS");

        assertThatThrownBy(() -> provider.readAttributes(
                fs.getPath("/f.txt"), BasicFileAttributes.class))
                        .isInstanceOf(java.io.IOException.class)
                        .hasMessageContaining(
                                "Unexpected WebHDFS response 500 for GETFILESTATUS");
    }

    @Test
    void testProviderKerberosAndListstatusBlankSuffixBranch() throws Exception {
        var provider = new HdfsFileSystemProvider();
        var client = mock(CloseableHttpClient.class);
        var resp = mock(CloseableHttpResponse.class);
        when(client.execute(any(HttpHost.class), any(HttpGet.class)))
                .thenReturn(resp);
        when(resp.getCode()).thenReturn(200);
        when(resp.getEntity()).thenReturn(new StringEntity(
                """
                {"FileStatuses":{"FileStatus":[
                  {"pathSuffix":"","type":"FILE","length":1,
                   "modificationTime":1,"accessTime":1},
                  {"pathSuffix":"kept.txt","type":"FILE","length":2,
                   "modificationTime":2,"accessTime":2}
                ]}}
                """));

        var fs = provider.getOrCreateFileSystem(
                URI.create("webhdfs://secure.example.com:9870/"),
                "alice", new Subject(), host -> client);

        try (var stream = provider.newDirectoryStream(
                fs.getPath("/root"), p -> false)) {
            assertThat(stream.iterator().hasNext()).isFalse();
        }
    }
}
