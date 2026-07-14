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
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.util.Iterator;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import jcifs.CIFSContext;
import jcifs.internal.dtyp.ACE;
import jcifs.smb.SmbFile;

class SmbModelTest {

    private static final URI URI_WITH_PATH =
            URI.create("smb://fileserver.example.com/share/root");

    private static final URI URI_WITHOUT_PORT =
            URI.create("smb://fileserver.example.com/share/root");

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
    void testProviderGetSchemeAndDefaultPort() {
        var provider = new SmbFileSystemProvider();
        var fs = provider.getOrCreateFileSystem(URI_WITHOUT_PORT,
                mock(CIFSContext.class));

        assertThat(provider.getScheme()).isEqualTo("smb");
        assertThat(fs.port()).isEqualTo(445);
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
        assertThatThrownBy(() -> nested.register(null,
                new WatchEvent.Kind<?>[0]))
                        .isInstanceOf(UnsupportedOperationException.class);
        assertThat(nested.resolveSibling("z.txt").toString())
                .isEqualTo("/z.txt");
        assertThat(root.resolveSibling("peer").toString()).isEqualTo("/peer");

        var otherFs = provider.getOrCreateFileSystem(
                URI.create("smb://other.example.com/share/root"),
                mock(CIFSContext.class));
        var samePath = fs.getPath("/share/a/c.txt");
        var samePathCopy = fs.getPath("/share/a/c.txt");
        var otherPath = otherFs.getPath("/share/a/c.txt");

        assertThat(samePath.startsWith(Path.of("/share"))).isFalse();
        assertThat(samePath.endsWith(Path.of("c.txt"))).isFalse();
        assertThat(samePath.startsWith(otherPath)).isFalse();
        assertThat(samePath.endsWith(otherPath)).isFalse();
        assertThat(samePath.equals("x")).isFalse();
        assertThat(samePath.equals(otherPath)).isFalse();
        assertThat(samePath.equals(samePathCopy)).isTrue();
        assertThat(samePath.hashCode()).isEqualTo(samePathCopy.hashCode());
        assertThat(samePath.resolve("tail")).isEqualTo(fs.getPath("/tail"));

        fs.close();
        fs.close();

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
        assertThat(fs.provider()).isSameAs(provider);
    }

    @Test
    void testProviderReadAttributesAndReadOnlyOps() throws Exception {
        var provider = new SmbFileSystemProvider();
        var ctx = mock(CIFSContext.class);
        var fs = provider.getOrCreateFileSystem(URI_WITH_PATH, ctx);
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
        provider.checkAccess(path, AccessMode.READ);
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

    @Test
    void testNewInputStreamAndByteChannel() throws Exception {
        var provider = new SmbFileSystemProvider();
        var fs = provider.getOrCreateFileSystem(URI_WITH_PATH,
                mock(CIFSContext.class));
        var path = fs.getPath("/share/a/file.txt");

        try (MockedConstruction<SmbFile> ignored = mockConstruction(
                SmbFile.class,
                (mocked, context) -> {
                    when(mocked.exists()).thenReturn(true);
                    when(mocked.getInputStream()).thenReturn(
                            new ByteArrayInputStream("hello".getBytes()));
                })) {
            try (var is = provider.newInputStream(path)) {
                assertThat(is.readAllBytes()).isEqualTo("hello".getBytes());
            }

            try (var channel =
                    provider.newByteChannel(path, java.util.Set.of())) {
                var dst = ByteBuffer.allocate(8);
                assertThat(channel.read(dst)).isEqualTo(5);
                assertThat(channel.read(ByteBuffer.allocate(1))).isEqualTo(-1);
                assertThat(channel.position()).isEqualTo(5);
                channel.position(1);
                assertThat(channel.position()).isEqualTo(1);
                assertThat(channel.size()).isEqualTo(5);
                assertThatThrownBy(() -> channel.write(ByteBuffer.wrap(
                        new byte[] { 1 }))).isInstanceOf(
                                NonWritableChannelException.class);
                assertThatThrownBy(() -> channel.truncate(1))
                        .isInstanceOf(NonWritableChannelException.class);
                assertThat(channel.isOpen()).isTrue();
            }
        }
    }

    @Test
    void testNewInputStreamNotFound() throws Exception {
        var provider = new SmbFileSystemProvider();
        var fs = provider.getOrCreateFileSystem(URI_WITH_PATH,
                mock(CIFSContext.class));
        var path = fs.getPath("/share/missing.txt");

        try (MockedConstruction<SmbFile> ignored = mockConstruction(
                SmbFile.class,
                (mocked, context) -> when(mocked.exists()).thenReturn(false))) {
            assertThatThrownBy(() -> provider.newInputStream(path))
                    .isInstanceOf(java.nio.file.NoSuchFileException.class);
        }
    }

    @Test
    void testNewDirectoryStreamAndGetAcl() throws Exception {
        var provider = new SmbFileSystemProvider();
        var fs = provider.getOrCreateFileSystem(URI_WITH_PATH,
                mock(CIFSContext.class));
        var dir = fs.getPath("/share/dir");
        var aclPath = fs.getPath("/share/dir/secure");
        var ace = mock(ACE.class);

        try (MockedConstruction<SmbFile> ignored = mockConstruction(
                SmbFile.class,
                (mocked, context) -> configureSmbFileMock(
                        mocked, context.getCount(), ace))) {
            try (DirectoryStream<Path> stream = provider.newDirectoryStream(
                    dir, p -> p.toString().endsWith(".txt"))) {
                Iterator<Path> it = stream.iterator();
                assertThat(it.hasNext()).isTrue();
                assertThat(it.next().toString()).isEqualTo("/share/dir/a.txt");
                assertThat(it.hasNext()).isFalse();
            }

            assertThat(provider.readAttributes(fs.getPath("/share/dir/a.txt"),
                    BasicFileAttributes.class).size()).isEqualTo(3L);

            assertThat(provider.getAcl((SmbPath) aclPath)).containsExactly(ace);
        }
    }

    @Test
    void testNewDirectoryStreamMissingAndNullFilter() throws Exception {
        var provider = new SmbFileSystemProvider();
        var fs = provider.getOrCreateFileSystem(URI_WITH_PATH,
                mock(CIFSContext.class));
        var dir = fs.getPath("/share/missing");

        try (MockedConstruction<SmbFile> ignored = mockConstruction(
                SmbFile.class,
                (mocked, context) -> {
                    when(mocked.exists()).thenReturn(context.getCount() != 1);
                    if (context.getCount() == 2) {
                        var child = mock(SmbFile.class);
                        when(child.getName()).thenReturn("x.dat");
                        when(child.isDirectory()).thenReturn(false);
                        when(child.length()).thenReturn(1L);
                        when(child.lastModified()).thenReturn(1L);
                        when(mocked.listFiles()).thenReturn(new SmbFile[] {
                                child
                        });
                    }
                })) {
            assertThatThrownBy(
                    () -> provider.newDirectoryStream(dir, p -> true))
                            .isInstanceOf(
                                    java.nio.file.NoSuchFileException.class);

            try (var stream =
                    provider.newDirectoryStream(fs.getPath("/share/ok"),
                            null)) {
                assertThat(stream.iterator()).hasNext();
            }
        }
    }

    @Test
    void testReadAttributesUncachedAndMissingAndAclFile() throws Exception {
        var provider = new SmbFileSystemProvider();
        var fs = provider.getOrCreateFileSystem(URI_WITH_PATH,
                mock(CIFSContext.class));
        var file = fs.getPath("/share/a/real.txt");
        var missing = fs.getPath("/share/a/missing.txt");

        try (MockedConstruction<SmbFile> ignored = mockConstruction(
                SmbFile.class,
                (mocked, context) -> {
                    if (context.getCount() == 1) {
                        when(mocked.exists()).thenReturn(true);
                        when(mocked.isDirectory()).thenReturn(false);
                        when(mocked.length()).thenReturn(9L);
                        when(mocked.lastModified()).thenReturn(77L);
                        return;
                    }
                    if (context.getCount() == 2) {
                        when(mocked.exists()).thenReturn(false);
                        return;
                    }
                    if (context.getCount() == 3) {
                        when(mocked.isDirectory()).thenReturn(false);
                        when(mocked.getSecurity())
                                .thenReturn(new ACE[] {
                                        mock(ACE.class)
                                });
                    }
                })) {
            var attrs =
                    provider.readAttributes(file, BasicFileAttributes.class);
            assertThat(attrs.size()).isEqualTo(9L);
            assertThat(fs.attrsCache()).containsKey(file.toString());

            assertThatThrownBy(() -> provider.readAttributes(missing,
                    BasicFileAttributes.class))
                            .isInstanceOf(
                                    java.nio.file.NoSuchFileException.class);

            assertThat(
                    provider.getAcl((SmbPath) fs.getPath("/share/a/aclfile")))
                            .hasSize(1);
        }
    }

    static void configureSmbFileMock(SmbFile mocked, int count, ACE ace)
            throws java.io.IOException {
        // Mockito construction index may be 0- or 1-based depending on version.
        var idx = count <= 0 ? 0 : count - 1;
        if (idx == 0) {
            when(mocked.exists()).thenReturn(true);

            var childFile = mock(SmbFile.class);
            when(childFile.getName()).thenReturn("a.txt");
            when(childFile.isDirectory()).thenReturn(false);
            when(childFile.length()).thenReturn(3L);
            when(childFile.lastModified()).thenReturn(100L);

            var childDir = mock(SmbFile.class);
            when(childDir.getName()).thenReturn("folder/");
            when(childDir.isDirectory()).thenReturn(true);
            when(childDir.length()).thenReturn(0L);
            when(childDir.lastModified()).thenReturn(200L);

            when(mocked.listFiles()).thenReturn(new SmbFile[] {
                    childFile, childDir
            });
            return;
        }
        if (idx == 1) {
            when(mocked.isDirectory()).thenReturn(true);
            return;
        }
        if (idx == 2) {
            when(mocked.getSecurity()).thenReturn(new ACE[] {
                    ace
            });
            return;
        }
        // Extra constructions are irrelevant for this scenario.
    }
}
