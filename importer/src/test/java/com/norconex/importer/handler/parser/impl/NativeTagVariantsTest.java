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

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_16;
import static java.nio.charset.StandardCharsets.UTF_16BE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.commons.lang.map.Properties;
import com.norconex.importer.Importer;
import com.norconex.importer.ImporterConfig;
import com.norconex.importer.ImporterRequest;

/**
 * The less common shapes of ID3 tags, RTF information groups and HTML
 * heads.
 */
@Timeout(60)
class NativeTagVariantsTest {

    @TempDir
    static Path folder;

    private static Properties importFile(Path file) throws IOException {
        try (var importer = new Importer(new ImporterConfig())) {
            var response = importer.importDocument(new ImporterRequest(file));
            assertThat(response.getDoc()).isNotNull();
            return response.getDoc().getMetadata();
        }
    }

    // ---- ID3 -------------------------------------------------------------

    private static byte[] mp3(int version, int flags, byte[] extended,
            byte[]... frames) throws IOException {
        var body = new ByteArrayOutputStream();
        body.write(extended);
        for (var f : frames) {
            body.write(f);
        }
        body.write(new byte[8]); // padding
        var n = body.size();
        var out = new ByteArrayOutputStream();
        out.write(new byte[] { 'I', 'D', '3', (byte) version, 0,
                (byte) flags });
        out.write(new byte[] { (byte) ((n >> 21) & 0x7f),
                (byte) ((n >> 14) & 0x7f), (byte) ((n >> 7) & 0x7f),
                (byte) (n & 0x7f) });
        out.write(body.toByteArray());
        var mpeg = new byte[417];
        mpeg[0] = (byte) 0xFF;
        mpeg[1] = (byte) 0xFB;
        mpeg[2] = (byte) 0x90;
        for (var i = 0; i < 4; i++) {
            out.write(mpeg);
        }
        return out.toByteArray();
    }

    /** A frame: id, then the payload as is. */
    private static byte[] frame(int version, String id, byte[] payload)
            throws IOException {
        var out = new ByteArrayOutputStream();
        out.write(id.getBytes(ISO_8859_1));
        var n = payload.length;
        if (version == 2) {
            out.write(new byte[] { (byte) (n >> 16), (byte) (n >> 8),
                    (byte) n });
        } else if (version == 3) {
            out.write(new byte[] { (byte) (n >> 24), (byte) (n >> 16),
                    (byte) (n >> 8), (byte) n, 0, 0 });
        } else {
            out.write(new byte[] { (byte) ((n >> 21) & 0x7f),
                    (byte) ((n >> 14) & 0x7f), (byte) ((n >> 7) & 0x7f),
                    (byte) (n & 0x7f), 0, 0 });
        }
        out.write(payload);
        return out.toByteArray();
    }

    private static byte[] payload(int encoding, byte[]... parts)
            throws IOException {
        var out = new ByteArrayOutputStream();
        out.write(encoding);
        for (var p : parts) {
            out.write(p);
        }
        return out.toByteArray();
    }

    private static byte[] bytes(String text, Charset cs) {
        return text.getBytes(cs);
    }

    private Properties importMp3(byte[] content) throws IOException {
        var file = Files.createTempFile(folder, "t", ".mp3");
        Files.write(file, content);
        return importFile(file);
    }

    @Test
    void id3v22FramesAreNamedWithTheirThreeLetterIds() throws IOException {
        var md = importMp3(mp3(2, 0, new byte[0],
                frame(2, "TT2", payload(0, bytes("Old Title", ISO_8859_1))),
                frame(2, "COM", payload(0, bytes("eng", ISO_8859_1),
                        bytes("note\0", ISO_8859_1),
                        bytes("A comment", ISO_8859_1)))));
        assertThat(md.getStrings("TT2")).containsExactly("Old Title");
        assertThat(md.getStrings("COM:note")).containsExactly("A comment");
    }

    @Test
    void id3v24ReadsUtf16Utf8AndUrlFramesAndSkipsTheExtendedHeader()
            throws IOException {
        var extended = new byte[] { 0, 0, 0, 6, 1, 0 };
        var md = importMp3(mp3(4, 0x40, extended,
                frame(4, "TIT2", payload(1, bytes("Utf16 Title", UTF_16))),
                frame(4, "TPE1", payload(3, bytes("Artïst", UTF_8))),
                frame(4, "TALB", payload(2, bytes("Álbum", UTF_16BE))),
                frame(4, "TCON", payload(0,
                        bytes("Rock\0Pop", ISO_8859_1))),
                frame(4, "WOAR", bytes("http://example.com/a", ISO_8859_1)),
                frame(4, "WXXX", payload(0, bytes("site\0", ISO_8859_1),
                        bytes("http://example.com/s", ISO_8859_1))),
                frame(4, "USLT", payload(1, bytes("eng", ISO_8859_1),
                        bytes("lyr", UTF_16), new byte[] { 0, 0 },
                        bytes("La la", UTF_16))),
                frame(4, "APIC", payload(0, new byte[] { 1, 2, 3 })),
                frame(4, "TXXX", payload(0, bytes("\0empty", ISO_8859_1)))));
        assertThat(md.getStrings("TIT2")).containsExactly("Utf16 Title");
        assertThat(md.getStrings("TPE1")).containsExactly("Artïst");
        assertThat(md.getStrings("TALB")).containsExactly("Álbum");
        assertThat(md.getStrings("TCON")).containsExactly("Rock", "Pop");
        assertThat(md.getStrings("WOAR"))
                .containsExactly("http://example.com/a");
        assertThat(md.getStrings("WXXX:site"))
                .containsExactly("http://example.com/s");
        assertThat(md.getStrings("USLT:lyr")).containsExactly("La la");
        assertThat(md.getStrings("TXXX")).containsExactly("empty");
        assertThat(md.containsKey("APIC")).isFalse();
    }

    @Test
    void id3v23ExtendedHeaderIsSkippedAndTruncatedFramesStopTheReading()
            throws IOException {
        var extended = new byte[] { 0, 0, 0, 6, 0, 0, 0, 0, 0, 0 };
        var good = frame(3, "TIT2", payload(0, bytes("Good", ISO_8859_1)));
        var lying = frame(3, "TPE1", payload(0, bytes("x", ISO_8859_1)));
        lying[7] = 100; // claims more than the tag holds
        var md = importMp3(mp3(3, 0x40, extended, good, lying));
        assertThat(md.getStrings("TIT2")).containsExactly("Good");
        assertThat(md.containsKey("TPE1")).isFalse();
    }

    @Test
    void anUnknownTagVersionIsLeftToTheParser() throws IOException {
        var md = importMp3(mp3(5, 0, new byte[0],
                frame(4, "TIT2", payload(0, bytes("Nope", ISO_8859_1)))));
        assertThat(md.containsKey("TIT2")).isFalse();
    }

    // ---- RTF -------------------------------------------------------------

    @Test
    void rtfEscapesAreResolvedAndOtherControlWordsAreIgnored()
            throws IOException {
        var file = folder.resolve("escapes.rtf");
        Files.writeString(file, "{\\rtf1\\ansi{\\info"
                + "{\\title Caf\\'e9 \\u8364? and \\b bold\\b0  \\\\ \\{x\\}}"
                + "{\\author\\* A}{\\subject}{\\comment 12 spaced }"
                + "{\\revtim\\yr2020\\mo5}"
                + "{no control word}}Hello}");
        var md = importFile(file);
        assertThat(md.getStrings("title"))
                .containsExactly("Café € and bold \\ {x}");
        assertThat(md.getStrings("comment")).containsExactly("12 spaced");
        assertThat(md.getStrings("revtim"))
                .containsExactly("2020-05-01T00:00:00");
        assertThat(md.containsKey("subject")).isFalse();
    }

    @Test
    void anRtfWithoutAnInfoGroupGivesNothingNamedByTheFile()
            throws IOException {
        var file = folder.resolve("plain.rtf");
        Files.writeString(file, "{\\rtf1\\ansi Hello}");
        assertThat(importFile(file).containsKey("title")).isFalse();
    }

    // ---- HTML ------------------------------------------------------------

    @Test
    void htmlMetaTagsAreNamedByHttpEquivNameOrProperty() throws IOException {
        var file = folder.resolve("page.html");
        Files.writeString(file, """
                <html><head>
                <meta http-equiv="refresh" content="30">
                <meta property="og:title" content="OG">
                <meta name="" content="no name">
                <meta name="empty" content=" ">
                <meta name="nocontent">
                <meta charset="utf-8">
                </head><body>x</body></html>""");
        var md = importFile(file);
        assertThat(md.getStrings("refresh")).containsExactly("30");
        assertThat(md.getStrings("og:title")).containsExactly("OG");
        assertThat(md.containsKey("empty")).isFalse();
        assertThat(md.containsKey("nocontent")).isFalse();
    }

    // ---- split documents -------------------------------------------------

    @Test
    void splitChildrenKeepTheirOwnNativeNamesAndTheParentHasNone()
            throws IOException {
        var zip = folder.resolve("two.zip");
        try (var out = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("word.doc"));
            try (var in = getClass()
                    .getResourceAsStream("/parser/msoffice/word.doc")) {
                out.write(in.readAllBytes());
            }
            out.closeEntry();
        }
        var config = new ImporterConfig();
        var parser = new DefaultParser();
        config.setHandlers(java.util.List.of(parser));
        parser.getConfiguration().getEmbeddedConfig().setSplitContentTypes(
                java.util.List.of(
                        com.norconex.commons.lang.text.TextMatcher
                                .regex(".*")));
        try (var importer = new Importer(config)) {
            var response = importer.importDocument(new ImporterRequest(zip));
            assertThat(response.getNestedResponses()).hasSize(1);
            var child = response.getNestedResponses().get(0).getDoc()
                    .getMetadata();
            assertThat(child.getStrings("Author"))
                    .containsExactly("Pascal Essiembre");
            assertThat(child.containsKey("dc:creator")).isFalse();
            assertThat(response.getDoc().getMetadata().containsKey("Author"))
                    .isFalse();
        }
    }

    // ---- ID3v1, Vorbis comments --------------------------------------------

    @Test
    void id3v1TagAtTheEndOfAnMp3IsRead() throws IOException {
        var content = new ByteArrayOutputStream();
        content.write(mp3(3, 0, new byte[0]));
        var tag = new byte[128];
        System.arraycopy("TAG".getBytes(ISO_8859_1), 0, tag, 0, 3);
        System.arraycopy("V1 Title".getBytes(ISO_8859_1), 0, tag, 3, 8);
        System.arraycopy("V1 Artist".getBytes(ISO_8859_1), 0, tag, 33, 9);
        System.arraycopy("V1 Album".getBytes(ISO_8859_1), 0, tag, 63, 8);
        System.arraycopy("2001".getBytes(ISO_8859_1), 0, tag, 93, 4);
        System.arraycopy("Remark".getBytes(ISO_8859_1), 0, tag, 97, 6);
        tag[126] = 7; // track (v1.1)
        tag[127] = 8; // Jazz
        content.write(tag);
        var md = importMp3(content.toByteArray());
        assertThat(md.getStrings("ID3v1:Title")).containsExactly("V1 Title");
        assertThat(md.getStrings("ID3v1:Artist"))
                .containsExactly("V1 Artist");
        assertThat(md.getStrings("ID3v1:Album")).containsExactly("V1 Album");
        assertThat(md.getStrings("ID3v1:Year")).containsExactly("2001");
        assertThat(md.getStrings("ID3v1:Comment")).containsExactly("Remark");
        assertThat(md.getStrings("ID3v1:Track")).containsExactly("7");
        assertThat(md.getStrings("ID3v1:Genre")).containsExactly("Jazz");
    }

    /** The reader alone: the parser may refuse hand-made audio files. */
    private static Properties readNative(byte[] content, String type)
            throws IOException {
        var md = new Properties();
        try (var in = org.apache.tika.io.TikaInputStream.get(content)) {
            com.norconex.importer.handler.parser.impl.nativemeta.NativeMetadata
                    .read(in,
                            com.norconex.commons.lang.file.ContentType
                                    .valueOf(type),
                            null)
                    .addTo(md);
        }
        return md;
    }

    private static byte[] vorbisComments(String... comments)
            throws IOException {
        var out = new ByteArrayOutputStream();
        var vendor = "test vendor".getBytes(UTF_8);
        out.write(le32(vendor.length));
        out.write(vendor);
        out.write(le32(comments.length));
        for (var c : comments) {
            var b = c.getBytes(UTF_8);
            out.write(le32(b.length));
            out.write(b);
        }
        return out.toByteArray();
    }

    private static byte[] le32(int n) {
        return new byte[] { (byte) n, (byte) (n >> 8), (byte) (n >> 16),
                (byte) (n >> 24) };
    }

    @Test
    void flacCommentsKeepTheirNames() throws IOException {
        var comments = vorbisComments("TITLE=Flac Title", "ARTIST=Flac Artist",
                "artist=Second", "MyCustom=Value",
                "METADATA_BLOCK_PICTURE=AAAA", "noequalsign", "EMPTY=");
        var out = new ByteArrayOutputStream();
        out.write("fLaC".getBytes(ISO_8859_1));
        out.write(0); // STREAMINFO, not last
        out.write(new byte[] { 0, 0, 34 });
        var info = new byte[34];
        info[0] = 0x10;
        info[2] = 0x10; // block sizes
        info[10] = 0x0A;
        info[11] = (byte) 0xC4;
        info[12] = 0x42;
        info[13] = (byte) 0xF0; // 44100 Hz, 2 channels, 16 bits
        out.write(info);
        out.write(0x84); // VORBIS_COMMENT, last
        out.write(new byte[] { (byte) (comments.length >> 16),
                (byte) (comments.length >> 8), (byte) comments.length });
        out.write(comments);
        out.write(new byte[64]);
        var md = readNative(out.toByteArray(), "audio/x-flac");
        System.out
                .println("KEYS " + md.keySet() + md.getStrings("Content-Type"));
        assertThat(md.getStrings("TITLE")).containsExactly("Flac Title");
        assertThat(md.getStrings("ARTIST")).containsExactly("Flac Artist");
        assertThat(md.getStrings("artist")).containsExactly("Second");
        assertThat(md.getStrings("MyCustom")).containsExactly("Value");
        assertThat(md.containsKey("METADATA_BLOCK_PICTURE")).isFalse();
        assertThat(md.containsKey("EMPTY")).isFalse();
    }

    @Test
    void oggVorbisCommentsKeepTheirNames() throws IOException {
        var out = new ByteArrayOutputStream();
        out.write("OggS".getBytes(ISO_8859_1));
        out.write(new byte[23]);
        out.write(new byte[] { 3, 'v', 'o', 'r', 'b', 'i', 's' });
        out.write(vorbisComments("TITLE=Ogg Title", "GENRE=Folk"));
        out.write(1);
        out.write(new byte[64]);
        var md = readNative(out.toByteArray(), "audio/ogg");
        assertThat(md.getStrings("TITLE")).containsExactly("Ogg Title");
        assertThat(md.getStrings("GENRE")).containsExactly("Folk");
    }
}
