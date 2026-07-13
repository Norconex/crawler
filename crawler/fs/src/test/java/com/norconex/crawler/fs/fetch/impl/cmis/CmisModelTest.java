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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.norconex.commons.lang.xml.Xml;

class CmisModelTest {

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
}
