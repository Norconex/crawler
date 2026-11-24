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
package com.norconex.importer.handler.parser.impl.nativemeta;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_16;
import static java.nio.charset.StandardCharsets.UTF_16BE;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;

import com.norconex.commons.lang.file.ContentType;

/**
 * Reads the ID3v1 tag at the end of an MP3 file ({@code ID3v1:Title},
 * {@code ID3v1:Artist}, ...) and the text frames of an ID3v2 tag at its
 * start, under
 * the frame identifier the tag used ({@code TIT2}, {@code TPE1},
 * {@code TALB}, ...). A user defined text ({@code TXXX}) or comment
 * ({@code COMM}) frame is named after its description, such as
 * {@code TXXX:MyTag}; and the URL frames ({@code W***}) as is. Tags of
 * versions 2.2, 2.3 and 2.4 are read. Pictures and other binary frames are
 * not fields.
 */
final class Mp3NativeMetadataReader implements NativeMetadataReader {

    private static final Set<String> TYPES =
            Set.of("audio/mpeg", "audio/mp3", "audio/x-mpeg");

    /** The tag is read up to this size, which leaves out large pictures. */
    private static final int MAX_TAG_BYTES = 4 * 1024 * 1024;

    private static final int ID3V1_BYTES = 128;

    /** ID3v1 genre numbers. */
    private static final List<String> GENRES = List.of(
            "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk",
            "Grunge", "Hip-Hop", "Jazz", "Metal", "New Age", "Oldies",
            "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno",
            "Industrial", "Alternative", "Ska", "Death Metal", "Pranks",
            "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal",
            "Jazz+Funk", "Fusion", "Trance", "Classical", "Instrumental",
            "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
            "Alternative Rock", "Bass", "Soul", "Punk", "Space",
            "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic",
            "Gothic", "Darkwave", "Techno-Industrial", "Electronic",
            "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy",
            "Cult", "Gangsta", "Top 40", "Christian Rap", "Pop/Funk",
            "Jungle", "Native American", "Cabaret", "New Wave", "Psychedelic",
            "Rave", "Showtunes", "Trailer", "Lo-Fi", "Tribal", "Acid Punk",
            "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll",
            "Hard Rock");

    /** Frames with a language after the encoding. */
    private static final Set<String> WITH_LANGUAGE =
            Set.of("COMM", "COM", "USLT", "ULT");

    /** Frames with a description before the value, which names them. */
    private static final Set<String> WITH_DESCRIPTION = Set.of(
            "TXXX", "TXX", "COMM", "COM", "USLT", "ULT", "WXXX", "WXX");

    /** What the parser makes of the tag, written under its own names. */
    static final List<String> TIKA_DERIVED = List.of(
            "dc:title", "dc:creator", "dc:subject", "dc:description",
            "xmpDM:album", "xmpDM:artist", "xmpDM:albumArtist",
            "xmpDM:composer", "xmpDM:genre", "xmpDM:releaseDate",
            "xmpDM:trackNumber", "xmpDM:discNumber", "xmpDM:compilation",
            "xmpDM:logComment", "xmpDM:engineer", "xmpDM:copyright");

    @Override
    public boolean supports(ContentType contentType) {
        return TYPES.contains(contentType.toBaseTypeString());
    }

    @Override
    public NativeFields read(TikaInputStream in, String charset)
            throws IOException {
        var found = NativeFields.newMap();
        readId3v2(in, found);
        readId3v1(in.getPath(), found);
        return found.isEmpty()
                ? NativeFields.none()
                : new NativeFields(found, Mp3NativeMetadataReader::supersede);
    }

    /** The 128 last bytes, when they start with {@code TAG}. */
    private static void readId3v1(Path file, Map<String, List<String>> found)
            throws IOException {
        var tail = new byte[ID3V1_BYTES];
        try (var raf = new RandomAccessFile(file.toFile(), "r")) {
            if (raf.length() < ID3V1_BYTES) {
                return;
            }
            raf.seek(raf.length() - ID3V1_BYTES);
            raf.readFully(tail);
        }
        if (tail[0] != 'T' || tail[1] != 'A' || tail[2] != 'G') {
            return;
        }
        add(found, "ID3v1:Title", new String(tail, 3, 30, ISO_8859_1));
        add(found, "ID3v1:Artist", new String(tail, 33, 30, ISO_8859_1));
        add(found, "ID3v1:Album", new String(tail, 63, 30, ISO_8859_1));
        add(found, "ID3v1:Year", new String(tail, 93, 4, ISO_8859_1));
        // v1.1: a zero byte then the track number, in the comment's end
        var commentLen = tail[125] == 0 && tail[126] != 0 ? 28 : 30;
        add(found, "ID3v1:Comment",
                new String(tail, 97, commentLen, ISO_8859_1));
        if (commentLen == 28) {
            add(found, "ID3v1:Track", Integer.toString(tail[126] & 0xff));
        }
        var genre = tail[127] & 0xff;
        if (genre < GENRES.size()) {
            add(found, "ID3v1:Genre", GENRES.get(genre));
        }
    }

    private static void readId3v2(
            TikaInputStream in, Map<String, List<String>> found)
            throws IOException {
        var head = NativeMetadata.peek(in, 10);
        if (head.length < 10 || head[0] != 'I'
                || head[1] != 'D' || head[2] != '3' || head[3] < 2
                || head[3] > 4) {
            return;
        }
        var version = head[3];
        var size = syncsafe(head, 6, 4);
        var bytes = NativeMetadata.peek(in,
                (int) Math.min((long) size + 10, MAX_TAG_BYTES));
        var idLen = version == 2 ? 3 : 4;
        var headerLen = version == 2 ? 6 : 10;
        var end = Math.min(bytes.length, 10 + size);
        var pos = 10;
        // an extended header (2.3, 2.4) sits before the frames
        if (version >= 3 && (head[5] & 0x40) != 0 && bytes.length > 14) {
            pos += version == 4
                    ? syncsafe(bytes, 10, 4)
                    : 4 + readInt(bytes, 10, 4);
        }
        // a zero byte is where the padding starts
        while (pos + headerLen <= end && bytes[pos] != 0) {
            var frameSize = frameSize(version, bytes, pos);
            var start = pos + headerLen;
            if (frameSize < 0 || start + frameSize > end) {
                break;
            }
            frame(found, new String(bytes, pos, idLen, ISO_8859_1), bytes,
                    start, frameSize);
            pos = start + frameSize;
        }
    }

    private static int frameSize(int version, byte[] b, int pos) {
        return switch (version) {
            case 2 -> readInt(b, pos + 3, 3);
            case 3 -> readInt(b, pos + 4, 4);
            default -> syncsafe(b, pos + 4, 4);
        };
    }

    private static void frame(
            Map<String, List<String>> found,
            String id, byte[] b, int start, int len) {
        var isUrl = id.startsWith("W");
        if (len < 2 || !isUrl && !id.startsWith("T")
                && !WITH_LANGUAGE.contains(id)) {
            return;
        }
        if (isUrl && !WITH_DESCRIPTION.contains(id)) {
            add(found, id, new String(b, start, len, ISO_8859_1));
            return;
        }
        var cs = charsetOf(b[start]);
        var from = start + 1 + (WITH_LANGUAGE.contains(id) ? 3 : 0);
        var to = start + len;
        var name = id;
        if (WITH_DESCRIPTION.contains(id)) {
            var sep = terminator(b, from, to, cs);
            var description =
                    clean(new String(b, from, Math.max(0, sep - from), cs));
            from = Math.min(to, sep + (twoByte(cs) ? 2 : 1));
            name = description.isEmpty() ? id : id + ":" + description;
            if (isUrl) {
                cs = ISO_8859_1; // the URL is not in the text encoding
            }
        }
        if (from > to) {
            return;
        }
        // multiple values are separated by a null character
        for (var value : clean(new String(b, from, to - from, cs))
                .split("\u0000")) {
            add(found, name, value);
        }
    }

    /** Byte order marks are not part of the text. */
    private static String clean(String text) {
        return text.replace('\uFEFF', ' ').trim();
    }

    private static void add(
            Map<String, List<String>> found, String name, String value) {
        var v = value.trim();
        if (!v.isEmpty()) {
            NativeFields.add(found, name, v);
        }
    }

    private static Charset charsetOf(byte encoding) {
        return switch (encoding) {
            case 1 -> UTF_16;
            case 2 -> UTF_16BE;
            case 3 -> UTF_8;
            default -> ISO_8859_1;
        };
    }

    private static boolean twoByte(Charset cs) {
        return cs.name().startsWith("UTF-16");
    }

    private static int terminator(byte[] b, int from, int to, Charset cs) {
        var step = twoByte(cs) ? 2 : 1;
        for (var i = from; i + step <= to; i += step) {
            if (b[i] == 0 && (step == 1 || b[i + 1] == 0)) {
                return i;
            }
        }
        return to;
    }

    private static int syncsafe(byte[] b, int at, int n) {
        var v = 0;
        for (var i = 0; i < n; i++) {
            v = (v << 7) | (b[at + i] & 0x7f);
        }
        return v;
    }

    private static int readInt(byte[] b, int at, int n) {
        var v = 0;
        for (var i = 0; i < n; i++) {
            v = (v << 8) | (b[at + i] & 0xff);
        }
        return v;
    }

    private static void supersede(Metadata tika) {
        TIKA_DERIVED.forEach(tika::remove);
    }
}
