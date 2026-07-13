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
package com.norconex.crawler.fs.fetch.impl.webdav;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicHeader;
import org.junit.jupiter.api.Test;

class WebDavModelTest {

    @Test
    void testFileAttributesDefaultsAndFlags() {
        var ts = FileTime.fromMillis(1234);
        var attrs = new WebDavFileAttributes(true, -5, ts);

        assertThat(attrs.isDirectory()).isTrue();
        assertThat(attrs.isRegularFile()).isFalse();
        assertThat(attrs.isSymbolicLink()).isFalse();
        assertThat(attrs.isOther()).isFalse();
        assertThat(attrs.size()).isZero();
        assertThat(attrs.lastModifiedTime()).isEqualTo(ts);
        assertThat(attrs.lastAccessTime()).isEqualTo(ts);
        assertThat(attrs.creationTime()).isEqualTo(ts);
        assertThat(attrs.fileKey()).isNull();
    }

    @Test
    void testFileSystemProviderAndPathOperations() throws Exception {
        var provider = new WebDavFileSystemProvider();
        var client = mock(CloseableHttpClient.class);
        var uri = URI.create("webdav://example.com:8080/root");
        var env = Map.of(
                WebDavFileSystemProvider.ENV_CLIENT_SUPPLIER,
                (Supplier<CloseableHttpClient>) () -> client);

        var fs = provider.getOrCreateFileSystem(uri, env);
        assertThat(provider.openFileSystems()).hasSize(1);
        assertThat(provider.getFileSystem(uri)).isSameAs(fs);
        assertThat(provider.getPath(uri).toString()).isEqualTo("/root");

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

        var p = (WebDavPath) child;
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
                .isEqualTo("webdav://example.com:8080/a/b/c.txt");
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
        verify(client).close();
        assertThat(provider.openFileSystems()).isEmpty();
        assertThatThrownBy(() -> provider.getFileSystem(uri))
                .isInstanceOf(FileSystemNotFoundException.class);
    }

    @Test
    void testPlainHttpFallbackAttributesAndReadAccess() throws Exception {
        var provider = new WebDavFileSystemProvider();
        var client = mock(CloseableHttpClient.class);
        var uri = URI.create("http://example.com/root");
        var env = Map.of(
                WebDavFileSystemProvider.ENV_CLIENT_SUPPLIER,
                (Supplier<CloseableHttpClient>) () -> client);
        var fs = provider.getOrCreateFileSystem(uri, env);

        var response = mock(CloseableHttpResponse.class);
        doReturn(200).when(response).getCode();
        doReturn(new BasicHeader("Content-Length", "42"))
                .when(response).getFirstHeader("Content-Length");
        doReturn(new BasicHeader("Last-Modified",
                "Sun, 12 Jul 2026 10:00:00 GMT"))
                        .when(response).getFirstHeader("Last-Modified");
        doReturn(new StringEntity("")).when(response).getEntity();
        doReturn(response).when(client).execute(
                any(HttpHost.class), any(ClassicHttpRequest.class));

        var path = fs.getPath("/root/file.txt");
        var attrs = provider.readAttributes(path,
                java.nio.file.attribute.BasicFileAttributes.class);

        assertThat(attrs.isRegularFile()).isTrue();
        assertThat(attrs.size()).isEqualTo(42L);
        assertThat(attrs.lastModifiedTime().toMillis()).isGreaterThan(0L);
        assertThatCode(() -> provider.checkAccess(path))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> provider.checkAccess(path,
                java.nio.file.AccessMode.WRITE))
                        .isInstanceOf(
                                java.nio.file.AccessDeniedException.class);
    }

    @Test
    void testWebDavDirectoryListingAndAttributeCaching() throws Exception {
        var provider = new WebDavFileSystemProvider();
        var client = mock(CloseableHttpClient.class);
        var uri = URI.create("webdav://example.com/root");
        var env = Map.of(
                WebDavFileSystemProvider.ENV_CLIENT_SUPPLIER,
                (Supplier<CloseableHttpClient>) () -> client);
        var fs = provider.getOrCreateFileSystem(uri, env);

        var propfind = mock(CloseableHttpResponse.class);
        doReturn(207).when(propfind).getCode();
        doReturn(new StringEntity("""
                <multistatus xmlns="DAV:">
                  <response>
                    <href>http://example.com/root/</href>
                    <propstat>
                      <prop>
                        <resourcetype><collection/></resourcetype>
                        <getcontentlength>0</getcontentlength>
                        <getlastmodified>Mon, 12 Jul 2026 10:00:00 GMT</getlastmodified>
                      </prop>
                      <status>HTTP/1.1 200 OK</status>
                    </propstat>
                  </response>
                  <response>
                    <href>http://example.com/root/file.txt</href>
                    <propstat>
                      <prop>
                        <resourcetype/>
                        <getcontentlength>7</getcontentlength>
                        <getlastmodified>Mon, 12 Jul 2026 11:00:00 GMT</getlastmodified>
                      </prop>
                      <status>HTTP/1.1 200 OK</status>
                    </propstat>
                  </response>
                </multistatus>
                """)).when(propfind).getEntity();
        doReturn(propfind).when(client).execute(
                any(HttpHost.class), any(ClassicHttpRequest.class));

        var listing =
                provider.newDirectoryStream(fs.getPath("/root"), p -> true);
        var children = new java.util.ArrayList<Path>();
        for (Path child : listing) {
            children.add(child);
        }

        assertThat(children).hasSize(1);
        assertThat(children.getFirst().toString()).isEqualTo("/root/file.txt");
        assertThat(provider.readAttributes(children.getFirst(),
                java.nio.file.attribute.BasicFileAttributes.class).size())
                        .isEqualTo(7L);
    }

    @Test
    void testProviderReadOnlyOperationsAreRejected() throws Exception {
        var provider = new WebDavFileSystemProvider();
        var client = mock(CloseableHttpClient.class);
        var uri = URI.create("webdav://example.com/root");
        var env = Map.of(
                WebDavFileSystemProvider.ENV_CLIENT_SUPPLIER,
                (Supplier<CloseableHttpClient>) () -> client);
        var fs = provider.getOrCreateFileSystem(uri, env);
        var path = fs.getPath("/root/file.txt");

        assertThat(provider.isSameFile(path, fs.getPath("/root/file.txt")))
                .isTrue();
        assertThat(provider.isHidden(path)).isFalse();
        assertThatThrownBy(() -> provider.getFileStore(path))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("do not expose file stores");
        assertThatThrownBy(() -> provider.createDirectory(path))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> provider.delete(path))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> provider.copy(path, path))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> provider.move(path, path))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> provider.setAttribute(path,
                "basic:lastModifiedTime", FileTime.fromMillis(0)))
                        .isInstanceOf(UnsupportedOperationException.class)
                        .hasMessageContaining("read-only");
        assertThatThrownBy(() -> provider.readAttributes(path, "basic:*"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Attribute-name based reads");
    }

    @Test
    void testFileSystemCreationAndLookupErrors() throws Exception {
        var provider = new WebDavFileSystemProvider();
        var client = mock(CloseableHttpClient.class);
        var uri = URI.create("webdav://example.com/root");
        var env = Map.of(
                WebDavFileSystemProvider.ENV_CLIENT_SUPPLIER,
                (Supplier<CloseableHttpClient>) () -> client);

        provider.newFileSystem(uri, env);
        assertThatThrownBy(() -> provider.newFileSystem(uri, env))
                .isInstanceOf(FileSystemAlreadyExistsException.class);
        assertThat(provider.getPath(uri).toString()).isEqualTo("/root");

        var missingUri = URI.create("webdav://missing.example.com/root");
        assertThatThrownBy(() -> provider.getPath(missingUri))
                .isInstanceOf(FileSystemNotFoundException.class);
    }

    @Test
    void testNewInputStreamAndByteChannelEdgeCases() throws Exception {
        var provider = new WebDavFileSystemProvider();
        var client = mock(CloseableHttpClient.class);
        var uri = URI.create("http://example.com/root");
        var env = Map.of(
                WebDavFileSystemProvider.ENV_CLIENT_SUPPLIER,
                (Supplier<CloseableHttpClient>) () -> client);
        var fs = provider.getOrCreateFileSystem(uri, env);
        var path = fs.getPath("/root/file.txt");

        var notFound = mock(CloseableHttpResponse.class);
        doReturn(404).when(notFound).getCode();
        doReturn(notFound).when(client).execute(
                any(HttpHost.class), any(ClassicHttpRequest.class));

        assertThatThrownBy(() -> provider.newInputStream(path))
                .isInstanceOf(NoSuchFileException.class);

        var ok = mock(CloseableHttpResponse.class);
        doReturn(200).when(ok).getCode();
        doReturn(new StringEntity("abc")).when(ok).getEntity();
        doReturn(ok).when(client).execute(
                any(HttpHost.class), any(ClassicHttpRequest.class));

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
    }
}
