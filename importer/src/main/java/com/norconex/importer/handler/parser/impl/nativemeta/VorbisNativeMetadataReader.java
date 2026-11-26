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
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;

import com.norconex.commons.lang.file.ContentType;

/**
 * Reads Vorbis comments, the {@code NAME=value} pairs of Ogg (Vorbis,
 * Opus) and FLAC files, under the names they were written with
 * ({@code TITLE}, {@code ARTIST}, {@code ALBUM}, ...). Pictures, which
 * those files may carry as a comment too, are not fields.
 */
final class VorbisNativeMetadataReader implements NativeMetadataReader {

    private static final Set<String> TYPES = Set.of(
            "audio/ogg", "audio/vorbis", "audio/x-ogg", "application/ogg",
            "audio/opus", "audio/x-flac", "audio/flac");

    /** Comments are looked for in the first bytes of the file. */
    private static final int MAX_BYTES = 1024 * 1024;

    private static final byte[] VORBIS = marker(3, "vorbis");
    private static final byte[] OPUS = "OpusTags".getBytes(ISO_8859_1);
    private static final String PICTURE = "METADATA_BLOCK_PICTURE";

    @Override
    public boolean supports(ContentType contentType) {
        return TYPES.contains(contentType.toBaseTypeString());
    }

    @Override
    public NativeFields read(TikaInputStream in, String charset)
            throws IOException {
        var bytes = NativeMetadata.peek(in, MAX_BYTES);
        var found = NativeFields.newMap();
        if (startsWith(bytes, 0, "fLaC".getBytes(ISO_8859_1))) {
            readFlac(bytes, found);
        } else if (startsWith(bytes, 0, "OggS".getBytes(ISO_8859_1))) {
            var at = indexOf(bytes, VORBIS);
            if (at >= 0) {
                comments(bytes, at + VORBIS.length, found);
            } else {
                at = indexOf(bytes, OPUS);
                if (at >= 0) {
                    comments(bytes, at + OPUS.length, found);
                }
            }
        }
        return found.isEmpty()
                ? NativeFields.none()
                : new NativeFields(found,
                        VorbisNativeMetadataReader::supersede);
    }

    /** Metadata blocks: a flag and type byte, then a 3-byte length. */
    private static void readFlac(
            byte[] b, Map<String, List<String>> found) {
        var pos = 4;
        var last = false;
        while (!last && pos + 4 <= b.length) {
            last = (b[pos] & 0x80) != 0;
            var type = b[pos] & 0x7f;
            var len = (b[pos + 1] & 0xff) << 16 | (b[pos + 2] & 0xff) << 8
                    | b[pos + 3] & 0xff;
            pos += 4;
            if (type == 4) {
                comments(b, pos, found);
            }
            pos += len;
        }
    }

    /** Vendor, then a count of comments, all with little-endian lengths. */
    private static void comments(
            byte[] b, int from, Map<String, List<String>> found) {
        var pos = from;
        if (pos + 4 > b.length) {
            return;
        }
        pos += 4 + le32(b, pos); // vendor
        if (pos < 0 || pos + 4 > b.length) {
            return;
        }
        var count = le32(b, pos);
        pos += 4;
        for (var i = 0; i < count && pos + 4 <= b.length; i++) {
            var len = le32(b, pos);
            pos += 4;
            if (len < 0 || pos + len > b.length) {
                return;
            }
            add(found, new String(b, pos, len, UTF_8));
            pos += len;
        }
    }

    private static void add(Map<String, List<String>> found, String comment) {
        var eq = comment.indexOf('=');
        if (eq <= 0) {
            return;
        }
        var name = comment.substring(0, eq).trim();
        var value = comment.substring(eq + 1).trim();
        if (!value.isEmpty() && !name.equalsIgnoreCase(PICTURE)) {
            NativeFields.add(found, name, value);
        }
    }

    private static int le32(byte[] b, int at) {
        return b[at] & 0xff | (b[at + 1] & 0xff) << 8
                | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
    }

    private static byte[] marker(int first, String text) {
        var t = text.getBytes(ISO_8859_1);
        var out = new byte[t.length + 1];
        out[0] = (byte) first;
        System.arraycopy(t, 0, out, 1, t.length);
        return out;
    }

    private static boolean startsWith(byte[] b, int at, byte[] prefix) {
        if (at + prefix.length > b.length) {
            return false;
        }
        for (var i = 0; i < prefix.length; i++) {
            if (b[at + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] b, byte[] what) {
        for (var i = 0; i + what.length <= b.length; i++) {
            if (startsWith(b, i, what)) {
                return i;
            }
        }
        return -1;
    }

    /** What the parser makes of the comments, under its own names. */
    private static void supersede(Metadata tika) {
        Mp3NativeMetadataReader.TIKA_DERIVED.forEach(tika::remove);
    }
}
