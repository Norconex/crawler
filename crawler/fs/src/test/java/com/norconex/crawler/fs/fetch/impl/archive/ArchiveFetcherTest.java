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
package com.norconex.crawler.fs.fetch.impl.archive;

import static com.norconex.crawler.core.fetch.FetchDirective.DOCUMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.crawler.core.fetch.FetchException;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.crawler.fs.fetch.FileFetchResponse;
import com.norconex.crawler.fs.fetch.FolderPathsFetchRequest;
import com.norconex.crawler.fs.fetch.FolderPathsFetchResponse;
import com.norconex.crawler.fs.fetch.FsPath;
import com.norconex.importer.doc.Doc;

@Timeout(30)
class ArchiveFetcherTest {

    @TempDir
    Path tempDir;

    @Test
    void testAcceptRequest() {
        var fetcher = new ArchiveFetcher();
        assertThat(fetcher.acceptRequest(new FileFetchRequest(
                new Doc("zip:file:///tmp/sample.zip!/a.txt"), DOCUMENT)))
                        .isTrue();
        assertThat(fetcher.acceptRequest(new FileFetchRequest(
                new Doc("tar:file:///tmp/sample.tar!/a.txt"), DOCUMENT)))
                        .isTrue();
        assertThat(fetcher.acceptRequest(new FileFetchRequest(
                new Doc("file:///tmp/sample.txt"), DOCUMENT)))
                        .isFalse();
    }

    @Test
    void testWriteRead() {
        assertThatNoException().isThrownBy(
                () -> BeanMapper.DEFAULT.assertWriteRead(
                        new ArchiveFetcher()));
    }

    @Test
    void testZipFetchChildPathsAndFileContent() throws Exception {
        var zip = createZip(tempDir.resolve("docs.zip"),
                List.of(
                        entry("folder/a.txt", "alpha"),
                        entry("folder/b.txt", "beta")));
        var rootRef = "zip:" + zip.toUri() + "!/folder";
        var fileRef = "jar:" + zip.toUri() + "!/folder/a.txt";

        var fetcher = new ArchiveFetcher();
        fetcher.fetcherStartup(null);
        try {
            var childResp = (FolderPathsFetchResponse) fetcher.fetch(
                    new FolderPathsFetchRequest(new Doc(rootRef)));

            assertThat(childResp.getProcessingOutcome())
                    .isEqualTo(ProcessingOutcome.NEW);
            assertThat(childResp.getChildPaths())
                    .extracting(FsPath::getUri)
                    .contains(fileRef, "jar:" + zip.toUri() + "!/folder/b.txt");

            var fileResp = (FileFetchResponse) fetcher.fetch(
                    new FileFetchRequest(new Doc(fileRef), DOCUMENT));
            assertThat(fileResp.getProcessingOutcome())
                    .isEqualTo(ProcessingOutcome.NEW);
            assertThat(fileResp.isFile()).isTrue();
            assertThat(fileResp.isFolder()).isFalse();

            var doc = new Doc(fileRef);
            var readResp = (FileFetchResponse) fetcher.fetch(
                    new FileFetchRequest(doc, DOCUMENT));
            assertThat(readResp.getProcessingOutcome())
                    .isEqualTo(ProcessingOutcome.NEW);
            assertThat(new String(doc.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8)).isEqualTo("alpha");
        } finally {
            fetcher.fetcherShutdown(null);
        }
    }

    @Test
    void testTarFetchChildPathsAndFileContent() throws Exception {
        var tar = createTar(tempDir.resolve("bundle.tar"),
                List.of(
                        entry("inner/one.txt", "one"),
                        entry("inner/two.txt", "two")));
        var rootRef = "tar:" + tar.toUri() + "!/inner";
        var fileRef = "tar:" + tar.toUri() + "!/inner/one.txt";

        var fetcher = new ArchiveFetcher();
        fetcher.fetcherStartup(null);
        try {
            var childResp = (FolderPathsFetchResponse) fetcher.fetch(
                    new FolderPathsFetchRequest(new Doc(rootRef)));

            assertThat(childResp.getProcessingOutcome())
                    .isEqualTo(ProcessingOutcome.NEW);
            assertThat(childResp.getChildPaths())
                    .extracting(FsPath::getUri)
                    .contains(fileRef,
                            "tar:" + tar.toUri() + "!/inner/two.txt");

            var doc = new Doc(fileRef);
            var fileResp = (FileFetchResponse) fetcher.fetch(
                    new FileFetchRequest(doc, DOCUMENT));
            assertThat(fileResp.getProcessingOutcome())
                    .isEqualTo(ProcessingOutcome.NEW);
            assertThat(new String(doc.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8)).isEqualTo("one");
        } finally {
            fetcher.fetcherShutdown(null);
        }
    }

    @Test
    void testGzipSingleFileFetchChildPathsAndFileContent() throws Exception {
        var gz = createGzip(tempDir.resolve("note.txt.gz"), "payload");
        var rootRef = "gz:" + gz.toUri() + "!/";
        var fileRef = "gz:" + gz.toUri() + "!/note.txt";

        var fetcher = new ArchiveFetcher();
        fetcher.fetcherStartup(null);
        try {
            var childResp = (FolderPathsFetchResponse) fetcher.fetch(
                    new FolderPathsFetchRequest(new Doc(rootRef)));

            assertThat(childResp.getProcessingOutcome())
                    .isEqualTo(ProcessingOutcome.NEW);
            assertThat(childResp.getChildPaths())
                    .extracting(FsPath::getUri)
                    .containsExactly(fileRef);

            var doc = new Doc(fileRef);
            var fileResp = (FileFetchResponse) fetcher.fetch(
                    new FileFetchRequest(doc, DOCUMENT));
            assertThat(fileResp.getProcessingOutcome())
                    .isEqualTo(ProcessingOutcome.NEW);
            assertThat(new String(doc.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8)).isEqualTo("payload");
        } finally {
            fetcher.fetcherShutdown(null);
        }
    }

    @Test
    void testTarEntryEscapingDestinationFailsFetch() throws Exception {
        var tar = createTar(tempDir.resolve("zip-slip.tar"),
                List.of(entry("../evil.txt", "bad")));
        var rootRef = "tar:" + tar.toUri() + "!/";

        var fetcher = new ArchiveFetcher();
        fetcher.fetcherStartup(null);
        try {
            assertThatThrownBy(() -> fetcher.fetch(
                    new FolderPathsFetchRequest(new Doc(rootRef))))
                            .isInstanceOf(FetchException.class)
                            .hasMessageContaining(
                                    "Could not fetch child paths")
                            .hasRootCauseMessage(
                                    "Archive entry escapes destination directory: ../evil.txt");
        } finally {
            fetcher.fetcherShutdown(null);
        }
    }

    @Test
    void testUnsupportedInnerReferenceFailsFetch() {
        var fetcher = new ArchiveFetcher();
        fetcher.fetcherStartup(null);
        try {
            assertThatThrownBy(() -> fetcher.fetch(
                    new FolderPathsFetchRequest(new Doc(
                            "zip:xyz://unsupported/archive.zip!/"))))
                                    .isInstanceOf(FetchException.class)
                                    .hasMessageContaining(
                                            "Could not fetch child paths")
                                    .hasRootCauseMessage(
                                            "Unsupported inner file system reference: xyz://unsupported/archive.zip");
        } finally {
            fetcher.fetcherShutdown(null);
        }
    }

    private static ArchiveEntry entry(String path, String content) {
        return new ArchiveEntry(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static Path createZip(Path zipPath, List<ArchiveEntry> entries)
            throws IOException {
        try (var out = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (ArchiveEntry entry : entries) {
                out.putNextEntry(new ZipEntry(entry.path()));
                out.write(entry.content());
                out.closeEntry();
            }
        }
        return zipPath;
    }

    private static Path createTar(Path tarPath, List<ArchiveEntry> entries)
            throws IOException {
        try (var out = new TarArchiveOutputStream(
                Files.newOutputStream(tarPath))) {
            for (ArchiveEntry entry : entries) {
                var tarEntry = new TarArchiveEntry(entry.path());
                tarEntry.setSize(entry.content().length);
                out.putArchiveEntry(tarEntry);
                out.write(entry.content());
                out.closeArchiveEntry();
            }
            out.finish();
        }
        return tarPath;
    }

    private static Path createGzip(Path gzPath, String content)
            throws IOException {
        try (var out = new GZIPOutputStream(Files.newOutputStream(gzPath))) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return gzPath;
    }

    private record ArchiveEntry(String path, byte[] content) {
    }
}
