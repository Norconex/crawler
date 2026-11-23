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
package com.norconex.importer.handler.parser.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.commons.lang.map.Properties;
import com.norconex.importer.Importer;
import com.norconex.importer.ImporterConfig;
import com.norconex.importer.ImporterRequest;

/**
 * Legacy Office, RTF, EPUB and MP3 files give their properties under the
 * names the files used.
 */
@Timeout(60)
class NativeOtherFieldNamesTest {

    @TempDir
    static Path folder;

    private static Properties importFile(Path file) throws IOException {
        try (var importer = new Importer(new ImporterConfig())) {
            var response = importer.importDocument(new ImporterRequest(file));
            assertThat(response.getDoc()).isNotNull();
            return response.getDoc().getMetadata();
        }
    }

    @Test
    void legacyWordKeepsItsPropertyNames() throws IOException {
        var file = folder.resolve("word.doc");
        try (var in = getClass()
                .getResourceAsStream("/parser/msoffice/word.doc")) {
            Files.write(file, in.readAllBytes());
        }
        var md = importFile(file);
        assertThat(md.getStrings("Author"))
                .containsExactly("Pascal Essiembre");
        assertThat(md.getStrings("LastAuthor"))
                .containsExactly("Pascal Essiembre");
        assertThat(md.getStrings("Company")).containsExactly("Norconex Inc.");
        assertThat(md.getStrings("ApplicationName"))
                .containsExactly("Microsoft Office Word");
        assertThat(md.getStrings("CreateTime")).hasSize(1);
        assertThat(md.keySet()).noneMatch(
                k -> k.startsWith("extended-properties:")
                        || k.startsWith("meta:") || k.startsWith("cp:"));
        assertThat(md.containsKey("dc:creator")).isFalse();
    }

    @Test
    void rtfInfoKeepsItsControlWordNames() throws IOException {
        var file = folder.resolve("sample.rtf");
        Files.writeString(file, "{\\rtf1\\ansi{\\info{\\title RTF Title}"
                + "{\\author RTF Author}{\\operator RTF Operator}"
                + "{\\subject RTF Subject}{\\keywords k1 k2}"
                + "{\\company RTF Co}{\\category RTF Cat}"
                + "{\\creatim\\yr2026\\mo1\\dy2\\hr3\\min4}}Hello}");
        var md = importFile(file);
        assertThat(md.getStrings("title")).containsExactly("RTF Title");
        assertThat(md.getStrings("author")).containsExactly("RTF Author");
        assertThat(md.getStrings("operator")).containsExactly("RTF Operator");
        assertThat(md.getStrings("keywords")).containsExactly("k1 k2");
        assertThat(md.getStrings("company")).containsExactly("RTF Co");
        assertThat(md.getStrings("creatim"))
                .containsExactly("2026-01-02T03:04:00");
        assertThat(md.containsKey("dc:title")).isFalse();
        assertThat(md.keySet()).noneMatch(
                k -> k.startsWith("extended-properties:")
                        || k.startsWith("cp:"));
    }

    @Test
    void epubKeepsItsPackageMetadataNames() throws IOException {
        var file = folder.resolve("sample.epub");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            var mime = "application/epub+zip"
                    .getBytes(StandardCharsets.UTF_8);
            var first = new ZipEntry("mimetype");
            first.setMethod(ZipEntry.STORED);
            first.setSize(mime.length);
            var crc = new CRC32();
            crc.update(mime);
            first.setCrc(crc.getValue());
            zip.putNextEntry(first);
            zip.write(mime);
            zip.closeEntry();
            put(zip, "META-INF/container.xml",
                    """
                    <?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                    <rootfiles><rootfile full-path="content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""");
            put(zip, "content.opf",
                    """
                    <?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:x</dc:identifier><dc:title>EPUB Title</dc:title>
                    <dc:creator>EPUB Creator</dc:creator><dc:language>en</dc:language>
                    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta><meta name="calibre:series" content="My Series"/></metadata>
                    <manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine></package>""");
            put(zip, "c1.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\">"
                    + "<head><title>C1</title></head><body><p>Hello</p></body></html>");
        }
        var md = importFile(file);
        assertThat(md.getStrings("dc:title")).containsExactly("EPUB Title");
        assertThat(md.getStrings("dc:creator"))
                .containsExactly("EPUB Creator");
        assertThat(md.getStrings("dcterms:modified"))
                .containsExactly("2026-01-01T00:00:00Z");
        assertThat(md.getStrings("calibre:series"))
                .containsExactly("My Series");
    }

    @Test
    void mp3KeepsItsId3FrameNames() throws IOException {
        var frames = new ByteArrayOutputStream();
        frame(frames, "TIT2", "MP3 Title");
        frame(frames, "TPE1", "MP3 Artist");
        frame(frames, "TALB", "MP3 Album");
        frame(frames, "TCON", "Jazz");
        frame(frames, "TXXX", "MyTag\0MyValue");
        var f = frames.toByteArray();
        var out = new ByteArrayOutputStream();
        out.write(new byte[] { 'I', 'D', '3', 3, 0, 0 });
        var n = f.length;
        out.write(new byte[] { (byte) ((n >> 21) & 0x7f),
                (byte) ((n >> 14) & 0x7f), (byte) ((n >> 7) & 0x7f),
                (byte) (n & 0x7f) });
        out.write(f);
        var mpeg = new byte[417];
        mpeg[0] = (byte) 0xFF;
        mpeg[1] = (byte) 0xFB;
        mpeg[2] = (byte) 0x90;
        for (var i = 0; i < 4; i++) {
            out.write(mpeg);
        }
        var file = folder.resolve("sample.mp3");
        Files.write(file, out.toByteArray());

        var md = importFile(file);
        assertThat(md.getStrings("TIT2")).containsExactly("MP3 Title");
        assertThat(md.getStrings("TPE1")).containsExactly("MP3 Artist");
        assertThat(md.getStrings("TALB")).containsExactly("MP3 Album");
        assertThat(md.getStrings("TCON")).containsExactly("Jazz");
        assertThat(md.getStrings("TXXX:MyTag")).containsExactly("MyValue");
        assertThat(md.containsKey("dc:title")).isFalse();
        assertThat(md.containsKey("xmpDM:album")).isFalse();
    }

    private static void frame(ByteArrayOutputStream out, String id, String text)
            throws IOException {
        var t = text.getBytes(StandardCharsets.ISO_8859_1);
        var size = t.length + 1;
        out.write(id.getBytes(StandardCharsets.US_ASCII));
        out.write(new byte[] { (byte) (size >> 24), (byte) (size >> 16),
                (byte) (size >> 8), (byte) size, 0, 0, 0 });
        out.write(t);
    }

    private static void put(ZipOutputStream zip, String name, String text)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
