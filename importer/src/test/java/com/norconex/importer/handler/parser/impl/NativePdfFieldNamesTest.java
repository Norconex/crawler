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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.commons.lang.map.Properties;
import com.norconex.importer.Importer;
import com.norconex.importer.ImporterConfig;
import com.norconex.importer.ImporterRequest;

/**
 * PDF information entries and XMP properties come out under the names the
 * file gave them.
 */
@Timeout(120)
class NativePdfFieldNamesTest {

    @TempDir
    static Path folder;

    private static final String XMP = """
            <?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:dc="http://purl.org/dc/elements/1.1/"
                xmlns:xmp="http://ns.adobe.com/xap/1.0/"
                xmlns:pdf="http://ns.adobe.com/pdf/1.3/"
                xmlns:acme="http://example.com/acme/"
                xmp:CreatorTool="XMP Tool"
                pdf:Producer="XMP Producer"
                acme:Custom="Custom XMP">
               <dc:title><rdf:Alt><rdf:li xml:lang="x-default">XMP Title</rdf:li></rdf:Alt></dc:title>
               <dc:creator><rdf:Seq><rdf:li>XMP Author 1</rdf:li><rdf:li>XMP Author 2</rdf:li></rdf:Seq></dc:creator>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>""";

    private static Path pdf(int pages, boolean withXmp) throws IOException {
        var file = Files.createTempFile(folder, "doc", ".pdf");
        try (var doc = new PDDocument()) {
            for (var i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            var info = doc.getDocumentInformation();
            info.setTitle("Info Title");
            info.setAuthor("Info Author");
            info.setSubject("Info Subject");
            info.setKeywords("k1, k2");
            info.setCreator("Info Creator");
            info.setProducer("Info Producer");
            info.setCreationDate(new java.util.GregorianCalendar(
                    2015, 2, 18, 21, 15, 59));
            info.setCustomMetadataValue("MyCustom", "Custom value");
            if (withXmp) {
                var md = new PDMetadata(doc,
                        new ByteArrayInputStream(
                                XMP.getBytes(StandardCharsets.UTF_8)));
                doc.getDocumentCatalog().setMetadata(md);
            }
            doc.save(file.toFile());
        }
        return file;
    }

    private static Properties importFile(Path file) throws IOException {
        try (var importer = new Importer(new ImporterConfig())) {
            var response = importer.importDocument(new ImporterRequest(file));
            assertThat(response.getDoc()).isNotNull();
            return response.getDoc().getMetadata();
        }
    }

    @Test
    void infoEntriesKeepTheirNames() throws IOException {
        var md = importFile(pdf(1, false));
        assertThat(md.getStrings("Title")).containsExactly("Info Title");
        assertThat(md.getStrings("Author")).containsExactly("Info Author");
        assertThat(md.getStrings("Subject")).containsExactly("Info Subject");
        assertThat(md.getStrings("Keywords")).containsExactly("k1, k2");
        assertThat(md.getStrings("Creator")).containsExactly("Info Creator");
        assertThat(md.getStrings("Producer")).containsExactly("Info Producer");
        assertThat(md.getStrings("MyCustom")).containsExactly("Custom value");
        assertThat(md.getString("CreationDate")).startsWith("D:2015");

        assertThat(md.keySet()).noneMatch(k -> k.startsWith("pdf:docinfo:"));
        assertThat(md.containsKey("dc:creator")).isFalse();
        assertThat(md.containsKey("dc:title")).isFalse();
        // what the parser works out stays
        assertThat(md.containsKey("pdf:encrypted")).isTrue();
    }

    @Test
    void xmpPropertiesKeepTheNamesThePacketGaveThem() throws IOException {
        var md = importFile(pdf(1, true));
        assertThat(md.getStrings("dc:title")).containsExactly("XMP Title");
        assertThat(md.getStrings("dc:creator"))
                .containsExactly("XMP Author 1", "XMP Author 2");
        assertThat(md.getStrings("xmp:CreatorTool"))
                .containsExactly("XMP Tool");
        assertThat(md.getStrings("pdf:Producer"))
                .containsExactly("XMP Producer");
        assertThat(md.getStrings("acme:Custom"))
                .containsExactly("Custom XMP");
        // and the info entries are still there under their own names
        assertThat(md.getStrings("Title")).containsExactly("Info Title");

        assertThat(md.keySet()).noneMatch(k -> k.startsWith("xmp-raw:"));
    }

    @Test
    void readingTheMetadataIsCheapOnALargeFile() throws IOException {
        var file = pdf(20_000, true);
        var start = System.nanoTime();
        for (var i = 0; i < 5; i++) {
            try (var doc = Loader.loadPDF(file.toFile())) {
                assertThat(doc.getDocumentInformation().getTitle())
                        .isEqualTo("Info Title");
                assertThat(doc.getDocumentCatalog().getMetadata()).isNotNull();
            }
        }
        var millis = (System.nanoTime() - start) / 5_000_000;
        System.out.println("PDF metadata read: " + millis + " ms for "
                + Files.size(file) / 1024 + " KB, 20000 pages");
        assertThat(millis).isLessThan(2000);
    }
}
