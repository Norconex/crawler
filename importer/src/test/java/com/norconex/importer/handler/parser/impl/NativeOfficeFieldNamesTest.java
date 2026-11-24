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
 * Properties of Office Open XML and OpenDocument files come out under the
 * names the files gave them.
 */
@Timeout(60)
class NativeOfficeFieldNamesTest {

    @TempDir
    static Path folder;

    private static Properties importFile(Path file) {
        try (var importer = new Importer(new ImporterConfig())) {
            var response = importer.importDocument(new ImporterRequest(file));
            assertThat(response.getDoc()).isNotNull();
            return response.getDoc().getMetadata();
        }
    }

    @Test
    void wordDocumentKeepsItsOoxmlNames() throws IOException {
        var file = folder.resolve("word.docx");
        try (var in = getClass()
                .getResourceAsStream("/parser/msoffice/word.docx")) {
            Files.write(file, in.readAllBytes());
        }
        var md = importFile(file);

        assertThat(md.getStrings("dc:creator"))
                .containsExactly("Pascal Essiembre");
        assertThat(md.getStrings("cp:lastModifiedBy"))
                .containsExactly("Pascal Essiembre");
        assertThat(md.getStrings("cp:revision")).containsExactly("2");
        assertThat(md.getStrings("dcterms:created"))
                .containsExactly("2015-03-11T14:35:00Z");
        assertThat(md.getStrings("Application"))
                .containsExactly("Microsoft Office Word");
        assertThat(md.getStrings("Company")).containsExactly("Norconex Inc.");
        assertThat(md.getStrings("Pages")).containsExactly("1");
        assertThat(md.getStrings("Words")).containsExactly("4");

        assertThat(md.keySet()).noneMatch(
                k -> k.startsWith("extended-properties:")
                        || k.startsWith("meta:"));
        assertThat(md.containsKey("dc:publisher")).isFalse();
    }

    @Test
    void openDocumentKeepsItsOwnNames() throws IOException {
        var file = folder.resolve("sample.odt");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            var mime = "application/vnd.oasis.opendocument.text"
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
            put(zip, "META-INF/manifest.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">
                    <manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.text"/>
                    <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
                    <manifest:file-entry manifest:full-path="meta.xml" manifest:media-type="text/xml"/>
                    </manifest:manifest>""");
            put(zip, "meta.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <office:document-meta xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:meta="urn:oasis:names:tc:opendocument:xmlns:meta:1.0" xmlns:dc="http://purl.org/dc/elements/1.1/" office:version="1.2"><office:meta>
                    <meta:generator>Gen/1.0</meta:generator><dc:title>ODT Title</dc:title>
                    <dc:subject>ODT Subject</dc:subject><meta:keyword>k1</meta:keyword>
                    <meta:initial-creator>ODT Initial</meta:initial-creator>
                    <dc:creator>ODT Creator</dc:creator>
                    <meta:creation-date>2026-01-02T03:04:05</meta:creation-date>
                    <meta:editing-cycles>3</meta:editing-cycles>
                    <meta:document-statistic meta:page-count="1" meta:word-count="2"/>
                    <meta:user-defined meta:name="MyProp">MyValue</meta:user-defined>
                    </office:meta></office:document-meta>""");
            put(zip, "content.xml",
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.2">
                    <office:body><office:text><text:p>Hello</text:p></office:text></office:body></office:document-content>""");
        }
        var md = importFile(file);

        assertThat(md.getStrings("dc:title")).containsExactly("ODT Title");
        assertThat(md.getStrings("dc:creator")).containsExactly("ODT Creator");
        assertThat(md.getStrings("meta:initial-creator"))
                .containsExactly("ODT Initial");
        assertThat(md.getStrings("meta:generator"))
                .containsExactly("Gen/1.0");
        assertThat(md.getStrings("meta:keyword")).containsExactly("k1");
        assertThat(md.getStrings("meta:creation-date"))
                .containsExactly("2026-01-02T03:04:05");
        assertThat(md.getStrings("meta:editing-cycles")).containsExactly("3");
        assertThat(md.getStrings("meta:page-count")).containsExactly("1");
        assertThat(md.getStrings("MyProp")).containsExactly("MyValue");

        assertThat(md.containsKey("generator")).isFalse();
        assertThat(md.containsKey("editing-cycles")).isFalse();
        assertThat(md.keySet()).noneMatch(
                k -> k.startsWith("custom:") || k.startsWith("odf:"));
        assertThat(md.containsKey("dcterms:created")).isFalse();
    }

    private static void put(ZipOutputStream zip, String name, String text)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
