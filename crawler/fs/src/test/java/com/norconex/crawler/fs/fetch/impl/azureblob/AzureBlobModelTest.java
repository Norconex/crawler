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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;

import com.azure.storage.blob.BlobContainerClient;

class AzureBlobModelTest {

    @Test
    void testFileAttributesDefaultsAndFlags() {
        var attrs = new AzureBlobFileAttributes(false, -5, null);

        assertThat(attrs.isRegularFile()).isTrue();
        assertThat(attrs.isDirectory()).isFalse();
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
        var provider = new AzureBlobFileSystemProvider();
        var location =
                new AzureBlobLocation("azblob", "acct", "container", "/");
        var fs = new AzureBlobFileSystem(provider, location,
                mock(BlobContainerClient.class));

        var root = fs.getPath("/");
        var child = fs.getPath("/folder/sub/file.txt");
        var sibling = fs.getPath("/folder/sub/other.txt");

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
        assertThat(ap.subpath(1, 3).toString()).isEqualTo("/sub/file.txt");
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
                .isEqualTo(fs.getPath("/folder/sub/file.txt").hashCode());

        assertThatThrownBy(ap::toFile)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ap.resolve(Path.of("x")))
                .isInstanceOf(IllegalArgumentException.class);

        fs.close();
        assertThat(fs.isOpen()).isFalse();
    }
}
