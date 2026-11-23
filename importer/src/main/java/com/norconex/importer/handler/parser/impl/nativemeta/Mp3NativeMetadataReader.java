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
import static java.nio.charset.StandardCharsets.UTF_16BE;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;

import com.norconex.commons.lang.file.ContentType;

/**
 * Reads the text frames of an ID3v2 tag at the start of an MP3 file, under
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

    /** What the parser makes of the tag, written under its own names. */
    private static final List<String> TIKA_DERIVED = List.of(
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
        var head = NativeMetadata.peek(in, 10);
        if (head == null || head.length < 10 || head[0] != 'I'
                || head[1] != 'D' || head[2] != '3') {
            return NativeFields.none();
        }
        var version = head[3];
        if (version < 2 || version > 4) {
            return NativeFields.none();
        }
        var size = syncsafe(head, 6, 4);
        var bytes = NativeMetadata.peek(in,
                (int) Math.min((long) size + 10, MAX_TAG_BYTES));
        if (bytes == null) {
            return NativeFields.none();
        }
        var found = NativeFields.newMap();
        var idLen = version == 2 ? 3 : 4;
        var headerLen = version == 2 ? 6 : 10;
        var pos = 10;
        // an extended header (2.3, 2.4) sits before the frames
        if (version >= 3 && (head[5] & 0x40) != 0 && bytes.length > 14) {
            pos += version == 4
                    ? syncsafe(bytes, 10, 4)
                    : 4 + readInt(bytes, 10, 4);
        }
        var end = Math.min(bytes.length, 10 + size);
        while (pos + headerLen <= end) {
            if (bytes[pos] == 0) {
                break; // padding
            }
            var id = new String(bytes, pos, idLen, ISO_8859_1);
            int frameSize;
            if (version == 2) {
                frameSize = readInt(bytes, pos + 3, 3);
            } else if (version == 3) {
                frameSize = readInt(bytes, pos + 4, 4);
            } else {
                frameSize = syncsafe(bytes, pos + 4, 4);
            }
            var start = pos + headerLen;
            if (frameSize < 0 || start + frameSize > end) {
                break;
            }
            frame(found, id, bytes, start, frameSize);
            pos = start + frameSize;
        }
        return found.isEmpty()
                ? NativeFields.none()
                : new NativeFields(found, Mp3NativeMetadataReader::supersede);
    }

    private static void frame(
            Map<String, List<String>> found,
            String id, byte[] b, int start, int len) {
        if (len < 2) {
            return;
        }
        var isText = id.startsWith("T") || id.equals("COM")
                || id.equals("COMM") || id.equals("ULT")
                || id.equals("USLT");
        var isUrl = id.startsWith("W");
        if (!isText && !isUrl) {
            return;
        }
        if (isUrl && !id.equals("WXXX") && !id.equals("WXX")) {
            add(found, id, new String(b, start, len, ISO_8859_1));
            return;
        }
        var cs = charsetOf(b[start]);
        var from = start + 1;
        var to = start + len;
        if (id.equals("COMM") || id.equals("COM") || id.equals("USLT")
                || id.equals("ULT")) {
            from += 3; // language
        }
        String name = id;
        if (id.equals("TXXX") || id.equals("TXX") || id.equals("COMM")
                || id.equals("COM") || id.equals("USLT") || id.equals("ULT")
                || id.equals("WXXX") || id.equals("WXX")) {
            var sep = terminator(b, from, to, cs);
            var description = new String(b, from, Math.max(0, sep - from), cs)
                    .replace('﻿', ' ').trim();
            from = Math.min(to, sep + (twoByte(cs) ? 2 : 1));
            if (!description.isEmpty()) {
                name = id + ":" + description;
            }
            if (id.startsWith("W")) {
                cs = ISO_8859_1; // the URL is not in the text encoding
            }
        }
        if (from > to) {
            return;
        }
        var text = new String(b, from, to - from, cs).replace('﻿', ' ');
        // multiple values are separated by a null character
        for (var value : text.split("\u0000")) {
            add(found, name, value);
        }
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
            case 1 -> Charset.forName("UTF-16");
            case 2 -> UTF_16BE;
            case 3 -> UTF_8;
            default -> ISO_8859_1;
        };
    }

    private static boolean twoByte(Charset cs) {
        return cs.name().startsWith("UTF-16");
    }

    private static int terminator(byte[] b, int from, int to, Charset cs) {
        if (twoByte(cs)) {
            for (var i = from; i + 1 < to; i += 2) {
                if (b[i] == 0 && b[i + 1] == 0) {
                    return i;
                }
            }
            return to;
        }
        for (var i = from; i < to; i++) {
            if (b[i] == 0) {
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
