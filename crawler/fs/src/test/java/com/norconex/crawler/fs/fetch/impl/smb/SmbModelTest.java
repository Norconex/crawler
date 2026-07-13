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
package com.norconex.crawler.fs.fetch.impl.smb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.nio.file.AccessMode;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

import jcifs.CIFSContext;

class SmbModelTest {

    @Test
    void testFileAttributesDefaultsAndFlags() {
        var attrs = new SmbFileAttributes(true, -2, 555L);

        assertThat(attrs.isDirectory()).isTrue();
        assertThat(attrs.isRegularFile()).isFalse();
        assertThat(attrs.isSymbolicLink()).isFalse();
        assertThat(attrs.isOther()).isFalse();
        assertThat(attrs.size()).isZero();
        assertThat(attrs.lastModifiedTime())
                .isEqualTo(FileTime.fromMillis(555));
        assertThat(attrs.lastAccessTime()).isEqualTo(FileTime.fromMillis(555));
        assertThat(attrs.creationTime()).isEqualTo(FileTime.fromMillis(555));
        assertThat(attrs.fileKey()).isNull();
    }

    @Test
    void testFileSystemProviderAndPathOperations() {
        var provider = new SmbFileSystemProvider();
        var ctx = mock(CIFSContext.class);
        var uri = URI.create("smb://fileserver.example.com:445/share/root");

        var fs = provider.getOrCreateFileSystem(uri, ctx);
        assertThat(provider.openFileSystems()).hasSize(1);
        assertThat(provider.getFileSystem(uri)).isSameAs(fs);
        assertThat(provider.getPath(uri).toString()).isEqualTo("/share/root");
        assertThat(fs.host()).isEqualTo("fileserver.example.com");
        assertThat(fs.port()).isEqualTo(445);

        var root = fs.getPath("/");
        var child = fs.getPath("/share/a/b/c.txt");
        var sibling = fs.getPath("/share/a/b/d.txt");

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

        var p = (SmbPath) child;
        assertThat(p.path()).isEqualTo("/share/a/b/c.txt");
        assertThat(p.getRoot()).isEqualTo(root);
        assertThat(p.getFileName().toString()).isEqualTo("/c.txt");
        assertThat(p.getParent().toString()).isEqualTo("/share/a/b");
        assertThat(p.getNameCount()).isEqualTo(4);
        assertThat(p.getName(1).toString()).isEqualTo("/a");
        assertThat(p.subpath(0, 2).toString()).isEqualTo("/share/a");
        assertThat(p.startsWith("/share")).isTrue();
        assertThat(p.endsWith("/c.txt")).isTrue();
        assertThat(p.normalize()).isSameAs(p);
        assertThat(p.resolveSibling(sibling)).isEqualTo(sibling);
        assertThat(p.relativize(sibling).toString()).isEqualTo("/../d.txt");
        assertThat(p.toUri().toString())
                .isEqualTo("smb://fileserver.example.com:445/share/a/b/c.txt");
        assertThat(p.toAbsolutePath()).isSameAs(p);
        assertThat(p.toRealPath()).isSameAs(p);
        assertThat((Iterable<Path>) p).hasSize(4);
        assertThat(p.compareTo(sibling)).isNegative();
        assertThat(p).isEqualTo(fs.getPath("/share/a/b/c.txt"));

        assertThatThrownBy(p::toFile)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> p.resolve(Path.of("x")))
                .isInstanceOf(IllegalArgumentException.class);

        fs.close();
        assertThat(fs.isOpen()).isFalse();
        assertThat(provider.openFileSystems()).isEmpty();
        assertThatThrownBy(() -> provider.getFileSystem(uri))
                .isInstanceOf(FileSystemNotFoundException.class);
    }

    @Test
    void testProviderAndPathEdgeCases() {
        var provider = new SmbFileSystemProvider();
        var ctx = mock(CIFSContext.class);
        var uri = URI.create("smb://fileserver.example.com/share/root");
        var fs = provider.getOrCreateFileSystem(uri, ctx);

        assertThat(fs.getPathMatcher("glob:*.txt")).isNotNull();
        assertThatThrownBy(() -> fs.getPathMatcher("regex:.*"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> fs.getPathMatcher("invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(fs::getUserPrincipalLookupService)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(fs::newWatchService)
                .isInstanceOf(UnsupportedOperationException.class);

        var root = fs.getPath("/");
        var nested = fs.getPath("/share/a/./b/../c.txt");

        assertThat(root.getFileName()).isNull();
        assertThat(root.getParent()).isNull();
        assertThat(nested.toString()).isEqualTo("/share/a/c.txt");
        assertThat(nested.startsWith("/share/a")).isTrue();
        assertThat(nested.endsWith("/c.txt")).isTrue();
        assertThat(nested.startsWith("/other")).isFalse();
        assertThat(nested.endsWith("/other.txt")).isFalse();
        assertThat(nested.relativize(fs.getPath("/share/a/d/e.txt")).toString())
                .isEqualTo("/../d/e.txt");
        assertThatThrownBy(() -> nested.relativize(Path.of("x")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(fs.getRootDirectories()).containsExactly(root);
        assertThat(fs.supportedFileAttributeViews()).containsExactly("basic");
        assertThat(fs.getFileStores()).isEmpty();
        assertThat(fs.getPath("/share", "folder", "file.txt").toString())
                .isEqualTo("/share/folder/file.txt");
        assertThat(fs.getPath("/share/a/b.txt").normalize())
                .isEqualTo(fs.getPath("/share/a/b.txt"));
        assertThat(fs.getPath("/share/a/b.txt").toAbsolutePath())
                .isEqualTo(fs.getPath("/share/a/b.txt"));

        assertThatThrownBy(() -> provider.newFileSystem(uri, Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testProviderReadAttributesAndReadOnlyOps() throws Exception {
        var provider = new SmbFileSystemProvider();
        var ctx = mock(CIFSContext.class);
        var uri = URI.create("smb://fileserver.example.com/share/root");
        var fs = provider.getOrCreateFileSystem(uri, ctx);
        var path = fs.getPath("/share/a/file.txt");

        fs.attrsCache().put(path.toString(),
                new SmbFileAttributes(false, 12L, 123L));

        assertThat(provider.readAttributes(path, BasicFileAttributes.class)
                .size()).isEqualTo(12L);
        assertThat(provider.readAttributes(path, SmbFileAttributes.class)
                .isRegularFile()).isTrue();
        assertThatThrownBy(() -> provider.readAttributes(path,
                java.nio.file.attribute.PosixFileAttributes.class))
                        .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.readAttributes(path, "basic:*"))
                .isInstanceOf(UnsupportedOperationException.class);

        var view = provider.getFileAttributeView(path,
                BasicFileAttributeView.class);
        assertThat(view).isNotNull();
        assertThat(view.name()).isEqualTo("basic");
        assertThat(view.readAttributes().size()).isEqualTo(12L);
        assertThatThrownBy(() -> view.setTimes(null, null, null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(provider.getFileAttributeView(path,
                FileAttributeView.class)).isNull();

        provider.checkAccess(path);
        assertThatThrownBy(() -> provider.checkAccess(path,
                AccessMode.WRITE))
                        .isInstanceOf(
                                java.nio.file.AccessDeniedException.class);
        assertThatThrownBy(() -> provider.checkAccess(path,
                AccessMode.EXECUTE))
                        .isInstanceOf(
                                java.nio.file.AccessDeniedException.class);
        assertThat(provider.isSameFile(path, fs.getPath("/share/a/file.txt")))
                .isTrue();
        assertThat(provider.isHidden(path)).isFalse();
        assertThatThrownBy(() -> provider.getFileStore(path))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.createDirectory(path))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.delete(path))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.copy(path, path))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.move(path, path))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.setAttribute(path, "x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
