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

import static com.norconex.crawler.core.fetch.FetchDirective.DOCUMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.MockedStatic;

import com.norconex.crawler.fs.doc.FsDocMetadata;
import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.crawler.fs.fetch.FileFetchResponse;
import com.norconex.crawler.fs.fetch.FolderPathsFetchRequest;
import com.norconex.crawler.fs.fetch.FolderPathsFetchResponse;
import com.norconex.importer.doc.Doc;

@Timeout(30)
class AdlsGen2FetcherIT {

    @Test
    void testFetchFileObject() throws IOException {
        var fetcher = spy(new AdlsGen2Fetcher());
        var path = mock(AdlsGen2Path.class);
        var attrs = mock(BasicFileAttributes.class);

        doReturn(path).when(fetcher).resolvePath(
                "abfss://filesystem@account.dfs.core.windows.net/bye.txt");
        when(path.getFileName()).thenReturn(null);
        when(attrs.isRegularFile()).thenReturn(true);
        when(attrs.isDirectory()).thenReturn(false);
        when(attrs.isSymbolicLink()).thenReturn(false);
        when(attrs.size()).thenReturn(10L);
        when(attrs.lastModifiedTime()).thenReturn(FileTime.fromMillis(1234));

        try (MockedStatic<Files> files = mockStatic(Files.class)) {
            files.when(() -> Files.readAttributes(
                    path, PosixFileAttributes.class))
                    .thenThrow(new UnsupportedOperationException());
            files.when(() -> Files.readAttributes(
                    path, BasicFileAttributes.class))
                    .thenReturn(attrs);
            files.when(() -> Files.newInputStream(path))
                    .thenReturn(new ByteArrayInputStream(
                            "Bye World!".getBytes()));
            files.when(() -> Files.isExecutable(path)).thenReturn(false);
            files.when(() -> Files.isReadable(path)).thenReturn(true);
            files.when(() -> Files.isWritable(path)).thenReturn(false);
            files.when(() -> Files.isHidden(path)).thenReturn(false);

            var doc = new Doc(
                    "abfss://filesystem@account.dfs.core.windows.net/bye.txt");
            var response = (FileFetchResponse) fetcher.fetch(
                    new FileFetchRequest(doc, DOCUMENT));

            assertThat(response.getProcessingOutcome().isGoodState())
                    .isTrue();
            assertThat(response.isFile()).isTrue();
            assertThat(response.isFolder()).isFalse();
            assertThat(new String(doc.getInputStream().readAllBytes()))
                    .contains("Bye World!");
            assertThat(doc.getMetadata().getString(FsDocMetadata.FILE_SIZE))
                    .isEqualTo("10");
        }
    }

    @Test
    void testFetchChildPaths() throws IOException {
        var fetcher = spy(new AdlsGen2Fetcher());
        var dir = mock(AdlsGen2Path.class);
        var file = mock(AdlsGen2Path.class);
        var folder = mock(AdlsGen2Path.class);

        doReturn(dir).when(fetcher).resolvePath(
                "abfss://filesystem@account.dfs.core.windows.net/");
        when(file.toUri()).thenReturn(URI.create(
                "abfss://filesystem@account.dfs.core.windows.net/bye.txt"));
        when(folder.toUri()).thenReturn(URI.create(
                "abfss://filesystem@account.dfs.core.windows.net/imgs"));

        DirectoryStream<Path> stream = new DirectoryStream<>() {
            @Override
            public java.util.Iterator<Path> iterator() {
                return List.<Path>of(file, folder).iterator();
            }

            @Override
            public void close() {
                //NOOP
            }
        };

        try (MockedStatic<Files> files = mockStatic(Files.class)) {
            files.when(() -> Files.newDirectoryStream(dir)).thenReturn(stream);
            files.when(() -> Files.isRegularFile(file)).thenReturn(true);
            files.when(() -> Files.isDirectory(file)).thenReturn(false);
            files.when(() -> Files.isRegularFile(folder)).thenReturn(false);
            files.when(() -> Files.isDirectory(folder)).thenReturn(true);

            var response = (FolderPathsFetchResponse) fetcher.fetch(
                    new FolderPathsFetchRequest(new Doc(
                            "abfss://filesystem@account.dfs.core.windows.net/")));

            assertThat(response.getProcessingOutcome().isGoodState()).isTrue();
            assertThat(response.getChildPaths())
                    .extracting(p -> p.getUri() + ":" + p.isFile() + ":"
                            + p.isFolder())
                    .containsExactlyInAnyOrder(
                            "abfss://filesystem@account.dfs.core.windows.net/bye.txt:true:false",
                            "abfss://filesystem@account.dfs.core.windows.net/imgs:false:true");
        }
    }
}
