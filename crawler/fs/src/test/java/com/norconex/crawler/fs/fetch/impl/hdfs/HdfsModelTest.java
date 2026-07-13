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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.Test;

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

        fs.close();
        assertThat(fs.isOpen()).isFalse();
        verify(client).close();
        assertThat(provider.openFileSystems()).isEmpty();
        assertThatThrownBy(() -> provider.getFileSystem(uri))
                .isInstanceOf(FileSystemNotFoundException.class);
    }
}
