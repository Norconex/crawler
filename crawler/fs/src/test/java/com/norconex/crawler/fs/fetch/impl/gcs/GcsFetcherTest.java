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
package com.norconex.crawler.fs.fetch.impl.gcs;

import static com.norconex.crawler.core.fetch.FetchDirective.DOCUMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.cloud.storage.HttpStorageOptions;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.storage.contrib.nio.CloudStorageFileSystem;
import com.google.cloud.storage.contrib.nio.CloudStoragePath;
import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.crawler.fs.FsTestUtil;
import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.importer.doc.Doc;

@Timeout(30)
class GcsFetcherTest {

    @Test
    void testAcceptRequest() {
        var f = new GcsFetcher();
        assertThat(f.acceptRequest(new FileFetchRequest(
                new Doc("gs://bucket/key"), DOCUMENT)))
                        .isTrue();
        assertThat(f.acceptRequest(new FileFetchRequest(
                new Doc("s3://bucket/key"), DOCUMENT)))
                        .isFalse();
    }

    @Test
    void testWriteRead() {
        assertThatNoException().isThrownBy(
                () -> BeanMapper.DEFAULT.assertWriteRead(
                        FsTestUtil.randomize(
                                GcsFetcher.class)));
    }

    @Test
    void testResolvePathCachesFileSystemAndNormalizesPaths()
            throws IOException {
        var f = spy(new GcsFetcher());
        var fs = mock(CloudStorageFileSystem.class);
        var objectPath = mock(CloudStoragePath.class);
        var rootPath = mock(CloudStoragePath.class);

        doReturn(fs).when(f).openFileSystem("bucket");
        when(fs.getPath("dir/file.txt")).thenReturn(objectPath);
        when(fs.getPath("/")).thenReturn(rootPath);

        assertThat(f.resolvePath("gs://bucket/dir/file.txt"))
                .isSameAs(objectPath);
        assertThat(f.resolvePath("gs://bucket"))
                .isSameAs(rootPath);
        assertThat(f.resolvePath("gs://bucket/"))
                .isSameAs(rootPath);

        verify(f, times(1)).openFileSystem("bucket");
    }

    @Test
    void testBuildStorageOptionsWithEndpoint() {
        var f = new GcsFetcher();
        f.getConfiguration().setEndpoint("http://localhost:4443");

        StorageOptions opts = f.buildStorageOptions();

        assertThat(opts).isInstanceOf(HttpStorageOptions.class);
        assertThat(opts.getHost()).isEqualTo("http://localhost:4443");
    }

    @Test
    void testFetcherShutdownClosesAndClearsOpenFileSystems()
            throws IOException {
        var f = spy(new GcsFetcher());
        var fs1 = mock(CloudStorageFileSystem.class);
        var fs2 = mock(CloudStorageFileSystem.class);
        var p1 = mock(CloudStoragePath.class);
        var p2 = mock(CloudStoragePath.class);

        doAnswer(inv -> "bucket1".equals(inv.getArgument(0)) ? fs1
                : fs2)
                        .when(f)
                        .openFileSystem(anyString());
        when(fs1.getPath("/")).thenReturn(p1);
        when(fs2.getPath("/")).thenReturn(p2);

        f.resolvePath("gs://bucket1");
        f.resolvePath("gs://bucket2");

        doAnswer(inv -> {
            throw new IOException("close failure");
        }).when(fs2).close();

        assertThatNoException()
                .isThrownBy(() -> f.fetcherShutdown(null));

        verify(fs1).close();
        verify(fs2).close();

        // Map should be cleared on shutdown; opening same bucket recreates FS.
        f.resolvePath("gs://bucket1");
        verify(f, times(2)).openFileSystem("bucket1");
    }
}
