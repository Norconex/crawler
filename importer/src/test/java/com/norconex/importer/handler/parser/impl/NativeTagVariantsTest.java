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
}
