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
package com.norconex.crawler.fs.fetch.impl.adlsgen2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.azure.core.http.rest.PagedIterable;
import com.azure.storage.file.datalake.DataLakeFileSystemClient;
import com.azure.storage.file.datalake.models.ListPathsOptions;
import com.azure.storage.file.datalake.models.PathItem;

class AdlsGen2ModelTest {

    @Test
    void testLocationParsingAndValidation() {
        var location = AdlsGen2Location.from(URI.create(
                "abfss://myfs@acct.dfs.core.windows.net/dir/file.txt"));
        assertThat(location.scheme()).isEqualTo("abfss");
        assertThat(location.fileSystem()).isEqualTo("myfs");
        assertThat(location.account()).isEqualTo("acct");
        assertThat(location.host()).isEqualTo("acct.dfs.core.windows.net");
        assertThat(location.path()).isEqualTo("/dir/file.txt");
        assertThat(location.key())
                .isEqualTo("abfss://myfs@acct.dfs.core.windows.net");

        assertThatThrownBy(() -> AdlsGen2Location.from(
                URI.create("abfss://acct.dfs.core.windows.net/path")))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("file system");
    }

    @Test
    void testFileAttributesDefaultsAndFlags() {
        var attrs = new AdlsGen2FileAttributes(true, -1, null);

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
    void testFileSystemProviderAndPathOperations() {
        var provider = new AdlsGen2FileSystemProvider();
        var location = AdlsGen2Location.from(URI.create(
                "abfss://myfs@acct.dfs.core.windows.net/root"));
        var client = mock(DataLakeFileSystemClient.class);
        var fs = provider.getOrCreateFileSystem(location, loc -> client);

        var lookupUri = URI.create(
                "abfss://myfs@acct.dfs.core.windows.net/root");
        assertThat(provider.openFileSystems()).hasSize(1);
        assertThat(provider.getFileSystem(lookupUri)).isSameAs(fs);
        assertThat(provider.getPath(lookupUri).toString()).isEqualTo("/root");
        assertThat(fs.scheme()).isEqualTo("abfss");
        assertThat(fs.fileSystemName()).isEqualTo("myfs");
        assertThat(fs.account()).isEqualTo("acct");
        assertThat(fs.host()).isEqualTo("acct.dfs.core.windows.net");

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

        var p = (AdlsGen2Path) child;
        assertThat(p.path()).isEqualTo("/a/b/c.txt");
        assertThat(p.pathName()).isEqualTo("a/b/c.txt");
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
                .isEqualTo("abfss://myfs@acct.dfs.core.windows.net/a/b/c.txt");
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
        assertThat(provider.openFileSystems()).isEmpty();
        assertThatThrownBy(() -> provider.getFileSystem(lookupUri))
                .isInstanceOf(FileSystemNotFoundException.class);
    }

    @Test
    void testDirectoryListingCachesChildAttributes() throws Exception {
        var provider = new AdlsGen2FileSystemProvider();
        provider.setAclDisabled(true);
        var location = AdlsGen2Location.from(URI.create(
                "abfss://myfs@acct.dfs.core.windows.net/root"));
        var client = mock(DataLakeFileSystemClient.class);
        var fs = provider.getOrCreateFileSystem(location, loc -> client);

        var items = List.of(
                new PathItem("etag1", OffsetDateTime.now(), 7L,
                        null, false, "root/file.txt", null, null),
                new PathItem("etag2", OffsetDateTime.now(), 0L,
                        null, true, "root/folder", null, null));
        @SuppressWarnings("unchecked")
        var iterable = mock(PagedIterable.class);
        when(iterable.iterator()).thenReturn(items.iterator());
        when(client.listPaths(any(ListPathsOptions.class), isNull()))
                .thenReturn(iterable);

        var listing =
                provider.newDirectoryStream(fs.getPath("/root"), p -> true);
        var children = new ArrayList<Path>();
        for (Path child : listing) {
            children.add(child);
        }

        assertThat(children).extracting(Path::toString)
                .containsExactlyInAnyOrder("/root/file.txt", "/root/folder");
        assertThat(provider.readAttributes(children.getFirst(),
                java.nio.file.attribute.BasicFileAttributes.class).size())
                        .isEqualTo(7L);
    }
}
