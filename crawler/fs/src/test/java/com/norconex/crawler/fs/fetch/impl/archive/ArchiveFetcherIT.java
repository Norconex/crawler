/* Copyright 2023-2026 Norconex Inc.
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.committer.core.UpsertRequest;
import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.crawler.fs.FsTestUtil;
import com.norconex.crawler.fs.fetch.impl.ftp.MockFtpServer;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ArchiveFetcherIT {

    // Per-test instance workdir
    @TempDir
    private Path tempDir;

    // --- Static FTP server setup (shared across FTP tests) ----------------

    private static MockFtpServer ftpServer;

    @BeforeAll
    static void startFtpServer(@TempDir File ftpTempDir)
            throws IOException {
        // Create a sub-directory that the FTP server will serve
        var servedDir = new File(ftpTempDir, "served");
        servedDir.mkdirs();
        createTestZip(servedDir.toPath().resolve("remote.zip"));

        ftpServer = new MockFtpServer(ftpTempDir, false)
                .setServedDir(servedDir.getAbsolutePath());
        ftpServer.start();
    }

    @AfterAll
    static void stopFtpServer() {
        MockFtpServer.stop(ftpServer);
    }

    // --- Local ZIP test ---------------------------------------------------

    /**
     * Crawls a local ZIP file without any server. Verifies that ArchiveFetcher
     * traverses the archive as a virtual directory tree and exposes each entry
     * as an individual document.
     */
    @Test
    void testLocalZip() throws Exception {
        var zipPath = tempDir.resolve("local.zip");
        createTestZip(zipPath);

        var fetcher = new ArchiveFetcher();
        var startUrl = "zip:" + zipPath.toUri();

        var mem = FsTestUtil.crawlWithFetcher(
                tempDir, fetcher, startUrl);

        assertThat(mem.getUpsertCount()).isEqualTo(2);
        assertThat(mem.getUpsertRequests())
                .map(UpsertRequest::getReference)
                .anyMatch(r -> r.endsWith("hello.txt"))
                .anyMatch(r -> r.endsWith("world.txt"));

        // Assert content was actually read
        assertThat(FsTestUtil.getUpsertRequestContent(
                mem, mem.getUpsertRequests().stream()
                        .map(UpsertRequest::getReference)
                        .filter(r -> r.endsWith(
                                "hello.txt"))
                        .findFirst().orElseThrow()))
                                .isEqualToIgnoringWhitespace(
                                        "Hello from archive!");
    }

    // --- Remote ZIP over FTP test -----------------------------------------

    /**
     * Crawls a ZIP file served over FTP. Verifies that ArchiveFetcher
     * correctly forwards credentials to the inner FTP layer via VFS's
     * StaticUserAuthenticator, and that archive traversal works identically
     * to the local case.
     */
    @Test
    void testRemoteZipOverFtp() throws Exception {
        var fetcher = new ArchiveFetcher();
        fetcher.getConfiguration()
                .getCredentials()
                .setUsername("testuser")
                .setPassword("testpassword");

        // VFS layered URI: zip:<inner-ftp-uri>
        // With the default userDirIsRoot behaviour, the path is relative
        // to the FTP user's home (= the servedDir we configured above).
        var startUrl = "zip:ftp://localhost:%d/remote.zip"
                .formatted(ftpServer.getPort());

        var mem = FsTestUtil.crawlWithFetcher(
                tempDir, fetcher, startUrl);

        assertThat(mem.getUpsertCount()).isEqualTo(2);
        assertThat(mem.getUpsertRequests())
                .map(UpsertRequest::getReference)
                .anyMatch(r -> r.endsWith("hello.txt"))
                .anyMatch(r -> r.endsWith("world.txt"));
    }

    // --- Local TAR test -----------------------------------------------

    /**
     * Crawls a local TAR (no compression) file, verifying the
     * commons-compress-backed extraction path.
     */
    @Test
    void testLocalTar() throws Exception {
        var tarPath = tempDir.resolve("local.tar");
        createTestTar(tarPath, null);

        var fetcher = new ArchiveFetcher();
        var startUrl = "tar:" + tarPath.toUri();

        var mem = FsTestUtil.crawlWithFetcher(tempDir, fetcher, startUrl);

        assertThat(mem.getUpsertCount()).isEqualTo(2);
        assertThat(mem.getUpsertRequests())
                .map(UpsertRequest::getReference)
                .anyMatch(r -> r.endsWith("hello.txt"))
                .anyMatch(r -> r.endsWith("world.txt"));
        assertThat(FsTestUtil.getUpsertRequestContent(
                mem, mem.getUpsertRequests().stream()
                        .map(UpsertRequest::getReference)
                        .filter(r -> r.endsWith("hello.txt"))
                        .findFirst().orElseThrow()))
                                .isEqualToIgnoringWhitespace(
                                        "Hello from archive!");
    }

    // --- Local TAR.GZ test ----------------------------------------------

    /**
     * Crawls a local gzip-compressed TAR file (tgz), verifying the
     * gzip decompression layer on top of tar extraction.
     */
    @Test
    void testLocalTarGz() throws Exception {
        var tgzPath = tempDir.resolve("local.tar.gz");
        createTestTar(tgzPath, "gz");

        var fetcher = new ArchiveFetcher();
        var startUrl = "tgz:" + tgzPath.toUri();

        var mem = FsTestUtil.crawlWithFetcher(tempDir, fetcher, startUrl);

        assertThat(mem.getUpsertCount()).isEqualTo(2);
        assertThat(mem.getUpsertRequests())
                .map(UpsertRequest::getReference)
                .anyMatch(r -> r.endsWith("hello.txt"))
                .anyMatch(r -> r.endsWith("world.txt"));
    }

    // --- Local single-file GZ test ---------------------------------------

    /**
     * Crawls a local gzip-compressed single file (not a tar), verifying
     * the single-file decompression path is exposed as one document.
     */
    @Test
    void testLocalGz() throws Exception {
        var gzPath = tempDir.resolve("hello.txt.gz");
        try (var out = new GzipCompressorOutputStream(
                Files.newOutputStream(gzPath))) {
            out.write("Hello from archive!"
                    .getBytes(StandardCharsets.UTF_8));
        }

        var fetcher = new ArchiveFetcher();
        var startUrl = "gz:" + gzPath.toUri();

        var mem = FsTestUtil.crawlWithFetcher(tempDir, fetcher, startUrl);

        assertThat(mem.getUpsertCount()).isEqualTo(1);
        assertThat(mem.getUpsertRequests())
                .map(UpsertRequest::getReference)
                .anyMatch(r -> r.endsWith("hello.txt"));
        assertThat(FsTestUtil.getUpsertRequestContent(
                mem, mem.getUpsertRequests().get(0).getReference()))
                        .isEqualToIgnoringWhitespace("Hello from archive!");
    }

    // --- BeanMapper round-trip test ---------------------------------------

    @Test
    void testWriteRead() {
        assertThatNoException().isThrownBy(
                () -> BeanMapper.DEFAULT.assertWriteRead(
                        FsTestUtil.randomize(
                                ArchiveFetcher.class)));
    }

    // --- helpers ----------------------------------------------------------

    /**
     * Creates a zip at {@code zipPath} containing two small text files with
     * known content.
     */
    static void createTestZip(Path zipPath) throws IOException {
        try (var zos = new ZipOutputStream(
                Files.newOutputStream(zipPath))) {
            addEntry(zos, "hello.txt", "Hello from archive!");
            addEntry(zos, "world.txt", "World from archive!");
        }
    }

    /**
     * Creates a tar at {@code tarPath} containing two small text files
     * with known content, optionally gzip-compressed when
     * {@code compression} is {@code "gz"}.
     */
    static void createTestTar(Path tarPath, String compression)
            throws IOException {
        OutputStream out = Files.newOutputStream(tarPath);
        if ("gz".equals(compression)) {
            out = new GzipCompressorOutputStream(out);
        }
        try (var tos = new TarArchiveOutputStream(out)) {
            addTarEntry(tos, "hello.txt", "Hello from archive!");
            addTarEntry(tos, "world.txt", "World from archive!");
            tos.finish();
        }
    }

    private static void addTarEntry(
            TarArchiveOutputStream tos, String name, String content)
            throws IOException {
        var bytes = content.getBytes(StandardCharsets.UTF_8);
        var entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        tos.putArchiveEntry(entry);
        tos.write(bytes);
        tos.closeArchiveEntry();
    }

    private static void addEntry(
            ZipOutputStream zos, String name, String content)
            throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes());
        zos.closeEntry();
    }
}
