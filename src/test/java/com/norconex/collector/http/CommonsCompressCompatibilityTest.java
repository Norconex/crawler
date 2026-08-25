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
package com.norconex.collector.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.commons.lang.map.Properties;
import com.norconex.importer.Importer;
import com.norconex.importer.ImporterConfig;
import com.norconex.importer.ImporterRequest;
import com.norconex.importer.response.ImporterResponse;

/**
 * <p>
 * Guards against the Commons Compress version resolved for this collector
 * drifting behind the one Tika is compiled against.
 * </p>
 * <p>
 * Tika detects ZIP containers (zip, docx, xlsx, pptx, odt, jar, epub, ...)
 * with {@code ZipArchiveInputStream.getNextEntry()}, which only returns a
 * {@link ZipArchiveEntry} as of Commons Compress 1.26.0. Older versions
 * return an {@code ArchiveEntry}, so Tika fails at runtime with:
 * </p>
 * <pre>
 * java.lang.NoSuchMethodError: 'org.apache.commons.compress.archivers.zip
 *     .ZipArchiveEntry org.apache.commons.compress.archivers.zip
 *     .ZipArchiveInputStream.getNextEntry()'
 * </pre>
 * <p>
 * See <a href="https://github.com/Norconex/crawler/issues/1319">issue
 * #1319</a>.
 * </p>
 */
class CommonsCompressCompatibilityTest {

    @TempDir
    private Path tempDir;

    @Test
    void testZipArchiveInputStreamHasCovariantGetNextEntry()
            throws NoSuchMethodException {
        Assertions.assertEquals(
                ZipArchiveEntry.class,
                ZipArchiveInputStream.class
                        .getMethod("getNextEntry").getReturnType(),
                () -> "Tika requires Commons Compress 1.26.0 or higher. "
                        + "Resolved instead: " + ZipArchiveInputStream.class
                                .getProtectionDomain().getCodeSource()
                                        .getLocation());
    }

    @Test
    void testZipContainerIsDetectedAndParsed() throws IOException {
        // Content type detection is performed on a stream (as opposed to a
        // file), which is what forces Tika down its streaming ZIP detection
        // path, where the missing method used to be invoked.
        Path zipFile = tempDir.resolve("issue-1319.zip");
        try (OutputStream out = Files.newOutputStream(zipFile)) {
            writeZip(out, "attachment.zip", nestedZipBytes());
        }

        ImporterResponse response = new Importer(new ImporterConfig())
                .importDocument(new ImporterRequest(zipFile)
                        .setMetadata(new Properties()));

        Assertions.assertNull(
                response.getImporterStatus().getException(),
                "Importing a ZIP container must not throw.");
        Assertions.assertEquals("application/zip",
                response.getDocument().getDocInfo()
                        .getContentType().toString(),
                "ZIP content type was not detected.");
        Assertions.assertTrue(
                new String(response.getDocument().getInputStream()
                        .readAllBytes(), StandardCharsets.UTF_8)
                                .contains("Norconex issue 1319"),
                "Embedded ZIP content was not extracted.");
    }

    private byte[] nestedZipBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeZip(out, "nested.txt",
                "Norconex issue 1319".getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private void writeZip(OutputStream out, String entryName, byte[] content)
            throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content);
            zos.closeEntry();
        }
    }
}
