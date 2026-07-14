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
package com.norconex.crawler.fs.fetch.impl.cmis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.norconex.commons.lang.xml.Xml;

class CmisModelTest {

    private static CmisTestServer cmisServer;

    @BeforeAll
    static void beforeAll() throws Exception {
        cmisServer = new CmisTestServer();
        cmisServer.start();
    }

    @AfterAll
    static void afterAll() throws Exception {
        if (cmisServer != null) {
            cmisServer.stop();
            cmisServer = null;
        }
    }

    private static URI endpointUri() {
        return URI.create("cmis:http://localhost:"
                + cmisServer.getLocalPort() + CmisTestServer.ATOM_1_0);
    }

    @Test
    void testFileAttributesRecognizedDocument() {
        var xml = new Xml("""
                <entry>
                  <object>
                    <properties>
                      <propertyString propertyDefinitionId="cmis:objectTypeId"><value>cmis:document</value></propertyString>
                      <propertyDateTime propertyDefinitionId="cmis:lastModificationDate"><value>2026-07-12T00:00:00Z</value></propertyDateTime>
                      <propertyInteger propertyDefinitionId="cmis:contentStreamLength"><value>42</value></propertyInteger>
                    </properties>
                  </object>
                </entry>
                """);

        var attrs = new CmisFileAttributes(xml);

        assertThat(attrs.isRecognized()).isTrue();
        assertThat(attrs.isRegularFile()).isTrue();
        assertThat(attrs.isDirectory()).isFalse();
        assertThat(attrs.size()).isEqualTo(42);
        assertThat(attrs.fileKey()).isNull();
        assertThat(attrs.getDocument()).isSameAs(xml);
    }

    @Test
    void testFileAttributesFallbackUnknownType() {
        var xml = new Xml("""
                <entry>
                  <object>
                    <properties>
                      <propertyString propertyDefinitionId="cmis:objectTypeId"><value>custom:type</value></propertyString>
                    </properties>
                  </object>
                </entry>
                """);

        var attrs = new CmisFileAttributes(xml);

        assertThat(attrs.isRecognized()).isFalse();
        assertThat(attrs.isDirectory()).isTrue();
        assertThat(attrs.isRegularFile()).isFalse();
        assertThat(attrs.size()).isZero();
    }

    @Test
    void testFileSystemPathAndEntryCaching() throws IOException {
        var provider = new CmisFileSystemProvider();
        var session = mock(CmisAtomSession.class);
        var xml = new Xml("<entry/> ");
        when(session.getDocumentByPath("/a/b")).thenReturn(xml);

        var fs = new CmisFileSystem(provider, "http://localhost:8080/cmis/atom",
                session);

        assertThat(fs.isOpen()).isTrue();
        assertThat(fs.isReadOnly()).isTrue();
        assertThat(fs.getSeparator()).isEqualTo("/");
        assertThat(fs.supportedFileAttributeViews()).containsExactly("basic");
        assertThat(fs.getRootDirectories()).hasSize(1);
        assertThat(fs.getFileStores()).isEmpty();

        var p = (CmisPath) fs.getPath("/a/b/c.txt");
        var sibling = fs.getPath("/a/b/d.txt");

        assertThat(p.path()).isEqualTo("/a/b/c.txt");
        assertThat(p.getFileName().toString()).isEqualTo("/c.txt");
        assertThat(p.getParent().toString()).isEqualTo("/a/b");
        assertThat(p.getNameCount()).isEqualTo(3);
        assertThat(p.getName(0).toString()).isEqualTo("/a");
        assertThat(p.subpath(0, 2).toString()).isEqualTo("/a/b");
        assertThat(p.startsWith("/a")).isTrue();
        assertThat(p.endsWith("/c.txt")).isTrue();
        assertThat(p.normalize()).isSameAs(p);
        assertThat(p.resolveSibling(sibling)).isEqualTo(sibling);
        assertThat(p.relativize(sibling).toString()).isEqualTo("/../d.txt");
        assertThat(p.toUri().toString())
                .isEqualTo("cmis:http://localhost:8080/cmis/atom!/a/b/c.txt");
        assertThat(p.toAbsolutePath()).isSameAs(p);
        assertThat(p.toRealPath()).isSameAs(p);
        assertThat((Iterable<Path>) p).hasSize(3);

        assertThatThrownBy(p::toFile)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> p.resolve(Path.of("x")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(fs.entry("/a/b")).isSameAs(xml);
        assertThat(fs.entry("/a/b")).isSameAs(xml);
        verify(session, times(1)).getDocumentByPath("/a/b");

        var glob = fs.getPathMatcher("glob:**/*.txt");
        assertThat(glob.matches(p)).isTrue();

        fs.close();
        assertThat(fs.isOpen()).isFalse();
        verify(session).close();
    }

    @Test
    void testProviderLifecycleAndPathResolution() throws Exception {
        var provider = new CmisFileSystemProvider();
        var env = Map.<String, Object>of();
        var uri = endpointUri();

        assertThat(provider.getScheme()).isEqualTo("cmis");

        var fs = provider.newFileSystem(uri, env);
        assertThat(fs).isInstanceOf(CmisFileSystem.class);
        assertThat(provider.openFileSystems()).hasSize(1);

        assertThatThrownBy(() -> provider.newFileSystem(uri, env))
                .isInstanceOf(FileSystemAlreadyExistsException.class);

        var sameFs = provider.getOrCreateFileSystem(uri, env);
        assertThat(sameFs).isSameAs(fs);
        assertThat(provider.getFileSystem(uri)).isSameAs(fs);

        var pathFromBang = provider.getPath(URI.create(
                "cmis:http://localhost:" + cmisServer.getLocalPort()
                        + CmisTestServer.ATOM_1_0 + "!/doc1.txt"));
        var pathNoBang = provider.getPath(endpointUri());

        assertThat(pathFromBang.toString()).isEqualTo("/doc1.txt");
        assertThat(pathNoBang.toString()).isEqualTo("/");

        provider.closeFileSystem(uri.getSchemeSpecificPart());
        assertThat(provider.openFileSystems()).isEmpty();
        assertThatThrownBy(() -> provider.getFileSystem(uri))
                .isInstanceOf(FileSystemNotFoundException.class);
    }

    @Test
    void testProviderReadAndDirectoryOperations() throws Exception {
        var provider = new CmisFileSystemProvider();
        var fs = (CmisFileSystem) provider.getOrCreateFileSystem(
                endpointUri(), Map.of());
        var root = fs.getPath("/");
        var doc = fs.getPath("/doc1.txt");

        try (var in = provider.newInputStream(doc)) {
            var content = new String(in.readAllBytes());
            assertThat(content).contains("doc1");
        }

        try (var in = provider.newInputStream(root)) {
            assertThat(in.readAllBytes()).isEmpty();
        }

        try (var ch = provider.newByteChannel(doc, java.util.Set.of())) {
            var dst = ByteBuffer.allocate(16);
            assertThat(ch.read(dst)).isPositive();
            dst.flip();
            assertThat(dst.remaining()).isPositive();
            assertThatThrownBy(
                    () -> ch.write(ByteBuffer.wrap(new byte[] { 1 })))
                            .isInstanceOf(
                                    java.nio.channels.NonWritableChannelException.class);
        }

        try (DirectoryStream<Path> stream = provider.newDirectoryStream(
                root, p -> p.toString().endsWith(".txt"))) {
            assertThat(stream).hasSize(21);
        }

        try (DirectoryStream<Path> stream = provider.newDirectoryStream(
                doc, null)) {
            assertThat(stream).isEmpty();
        }
    }

    @Test
    void testProviderAttributesAndReadOnlyGuards() throws Exception {
        var provider = new CmisFileSystemProvider();
        var fs = (CmisFileSystem) provider.getOrCreateFileSystem(
                endpointUri(), Map.of());
        var doc = fs.getPath("/doc2.txt");

        var attrs = provider.readAttributes(doc, BasicFileAttributes.class);
        assertThat(attrs.isRegularFile()).isTrue();

        var cmisAttrs = provider.readAttributes(doc, CmisFileAttributes.class);
        assertThat(cmisAttrs.isRegularFile()).isTrue();

        assertThatThrownBy(() -> provider.readAttributes(
                doc, java.nio.file.attribute.PosixFileAttributes.class))
                        .isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> provider.readAttributes(
                doc, "basic:size"))
                        .isInstanceOf(UnsupportedOperationException.class);

        var view = provider.getFileAttributeView(
                doc, BasicFileAttributeView.class);
        assertThat(view).isNotNull();
        assertThat(view.name()).isEqualTo("basic");
        assertThat(view.readAttributes().isRegularFile()).isTrue();
        assertThatThrownBy(() -> view.setTimes(null, null, null))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(provider.getFileAttributeView(
                doc,
                java.nio.file.attribute.PosixFileAttributeView.class))
                        .isNull();

        assertThatNoException().isThrownBy(() -> provider.checkAccess(doc));
        assertThatThrownBy(() -> provider.checkAccess(doc, AccessMode.WRITE))
                .isInstanceOf(java.nio.file.AccessDeniedException.class);
        assertThatThrownBy(() -> provider.checkAccess(doc, AccessMode.EXECUTE))
                .isInstanceOf(java.nio.file.AccessDeniedException.class);

        var unknownXml = new Xml("""
          <entry>
            <object>
              <properties>
                <propertyString propertyDefinitionId="cmis:objectTypeId"><value>custom:type</value></propertyString>
              </properties>
            </object>
          </entry>
          """);
        var mockSession = mock(CmisAtomSession.class);
        when(mockSession.getDocumentByPath("/unknown")).thenReturn(unknownXml);
        var fakeFs = new CmisFileSystem(provider, "http://localhost/fake",
                mockSession);
        assertThatThrownBy(
                () -> provider.checkAccess(fakeFs.getPath("/unknown")))
                        .isInstanceOf(NoSuchFileException.class);

        assertThat(provider.isHidden(doc)).isFalse();
        assertThat(provider.isSameFile(doc, doc)).isTrue();
        assertThatThrownBy(() -> provider.getFileStore(doc))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.createDirectory(doc))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.delete(doc))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.copy(doc, doc))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.move(doc, doc))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> provider.setAttribute(doc, "a", "b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
