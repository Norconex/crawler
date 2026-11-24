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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.james.mime4j.codec.DecodeMonitor;
import org.apache.james.mime4j.codec.DecoderUtil;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;

import com.norconex.commons.lang.file.ContentType;

/**
 * Reads the header of an e-mail message, under the names the message gave
 * them ({@code From}, {@code Subject}, {@code X-Mailer}, and so on, case as
 * written). Encoded words ({@code =?UTF-8?Q?...?=}) are decoded and folded
 * lines are joined, as a mail reader would show them.
 * <p>
 * The mail parser writes the standard headers under names of its own
 * ({@code message:from}, {@code dc:subject}, ...) and the rest under
 * {@code message:raw-header:}, and adds a split of the sender into name and
 * address. All of that is replaced by what the header says.
 * </p>
 * <p>
 * {@code Content-Type} is kept too. The document keeps the parser's value
 * (the message, such as {@code message/rfc822}) first, then the header's
 * (the body, such as {@code text/plain; charset=UTF-8}), always in that order.
 * </p>
 */
final class EmailNativeMetadataReader implements NativeMetadataReader {

    private static final Set<String> TYPES =
            Set.of("message/rfc822", "message/news");

    /** The header is read up to this size, or to its first blank line. */
    private static final int MAX_HEADER_BYTES = 256 * 1024;

    /** What the parser derives from the header, beside its own prefix. */
    private static final List<String> TIKA_DERIVED = List.of(
            "dc:creator", "dc:subject", "dc:title", "dcterms:created");

    @Override
    public boolean supports(ContentType contentType) {
        return TYPES.contains(contentType.toBaseTypeString());
    }

    @Override
    public NativeFields read(TikaInputStream in, String charset)
            throws IOException {
        var bytes = NativeMetadata.peek(in, MAX_HEADER_BYTES);
        if (bytes == null || bytes.length == 0) {
            return NativeFields.none();
        }
        var header = text(bytes, headerLength(bytes));

        var found = NativeFields.newMap();
        unfold(header).forEach(line -> addHeader(found, line));
        if (found.isEmpty()) {
            return NativeFields.none();
        }
        return new NativeFields(found, EmailNativeMetadataReader::supersede);
    }

    private static void addHeader(
            java.util.Map<String, List<String>> found, String line) {
        var colon = line.indexOf(':');
        if (colon <= 0) {
            return;
        }
        var name = line.substring(0, colon).trim();
        var value = DecoderUtil.decodeEncodedWords(
                line.substring(colon + 1).trim(), DecodeMonitor.SILENT);
        if (!name.isEmpty() && name.indexOf(' ') < 0 && !value.isBlank()) {
            NativeFields.add(found, name, value);
        }
    }

    private static void supersede(Metadata tika) {
        for (var name : tika.names()) {
            if (name.startsWith("message:")) {
                tika.remove(name);
            }
        }
        TIKA_DERIVED.forEach(tika::remove);
    }

    /** The header ends at the first blank line. */
    private static int headerLength(byte[] b) {
        for (var i = 0; i < b.length; i++) {
            if (b[i] == '\n') {
                if (i + 1 < b.length && b[i + 1] == '\n') {
                    return i;
                }
                if (i + 2 < b.length && b[i + 1] == '\r'
                        && b[i + 2] == '\n') {
                    return i;
                }
            }
        }
        return b.length;
    }

    /** UTF-8 if the header is valid UTF-8, else one byte per character. */
    private static String text(byte[] b, int len) {
        try {
            return UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, 0, len)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, 0, len, ISO_8859_1);
        }
    }

    /** Joins continuation lines (starting with white space) to their field. */
    private static List<String> unfold(String header) {
        var lines = new ArrayList<String>();
        for (var raw : header.split("\r?\n")) {
            if (!raw.isEmpty() && (raw.charAt(0) == ' '
                    || raw.charAt(0) == '\t') && !lines.isEmpty()) {
                var last = lines.size() - 1;
                lines.set(last, lines.get(last) + " " + raw.trim());
            } else {
                lines.add(raw);
            }
        }
        return lines;
    }
}
