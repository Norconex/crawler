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

import static java.nio.charset.Charset.forName;
import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.tika.io.TikaInputStream;

import com.norconex.commons.lang.file.ContentType;

/**
 * Reads the {@code \info} group of an RTF document: each entry under the
 * name of its control word ({@code title}, {@code author},
 * {@code operator}, {@code creatim}, {@code company}, ...). Dates, which
 * the file spells out as {@code \yr2026\mo1\dy2\hr3\min4}, are written as
 * {@code 2026-01-02T03:04:00}.
 */
final class RtfNativeMetadataReader implements NativeMetadataReader {

    private static final Set<String> TYPES =
            Set.of("application/rtf", "text/rtf");

    private static final int MAX_BYTES = 256 * 1024;

    private static final Pattern DATE_PART =
            Pattern.compile("\\\\(yr|mo|dy|hr|min|sec)(\\d+)");

    @Override
    public boolean supports(ContentType contentType) {
        return TYPES.contains(contentType.toBaseTypeString());
    }

    @Override
    public NativeFields read(TikaInputStream in, String charset)
            throws IOException {
        var bytes = NativeMetadata.peek(in, MAX_BYTES);
        if (bytes == null || bytes.length == 0) {
            return NativeFields.none();
        }
        var rtf = new String(bytes, ISO_8859_1);
        var start = rtf.indexOf("{\\info");
        if (start < 0) {
            return NativeFields.none();
        }
        var end = endOfGroup(rtf, start);
        var info = rtf.substring(start + "{\\info".length(), end);

        var found = NativeFields.newMap();
        var i = 0;
        while (i < info.length()) {
            if (info.charAt(i) != '{') {
                i++;
                continue;
            }
            var groupEnd = endOfGroup(info, i);
            var group = info.substring(i + 1, groupEnd);
            i = groupEnd + 1;
            var m = Pattern.compile("^\\\\([a-z]+)(.*)$", Pattern.DOTALL)
                    .matcher(group);
            if (!m.matches()) {
                continue;
            }
            var name = m.group(1);
            var rest = m.group(2);
            var value = DATE_PART.matcher(rest).find()
                    ? date(rest)
                    : text(rest);
            if (!value.isBlank()) {
                NativeFields.add(found, name, value);
            }
        }
        return found.isEmpty()
                ? NativeFields.none()
                : new NativeFields(found, OoxmlNativeMetadataReader::supersede);
    }

    private static int endOfGroup(String s, int open) {
        var depth = 0;
        for (var i = open; i < s.length(); i++) {
            var c = s.charAt(i);
            if (c == '\\') {
                i++; // escaped brace or control symbol
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return s.length();
    }

    private static String date(String rest) {
        var parts = new java.util.HashMap<String, Integer>();
        var m = DATE_PART.matcher(rest);
        while (m.find()) {
            parts.put(m.group(1), Integer.parseInt(m.group(2)));
        }
        return String.format("%04d-%02d-%02dT%02d:%02d:%02d",
                parts.getOrDefault("yr", 0), parts.getOrDefault("mo", 1),
                parts.getOrDefault("dy", 1), parts.getOrDefault("hr", 0),
                parts.getOrDefault("min", 0), parts.getOrDefault("sec", 0));
    }

    /** Plain text of the rest of a group: escapes resolved. */
    private static String text(String rest) {
        var sb = new StringBuilder();
        var i = 0;
        // one space ends the control word
        if (!rest.isEmpty() && rest.charAt(0) == ' ') {
            i = 1;
        } else {
            // control word parameter, such as \title12
            while (i < rest.length() && (Character.isDigit(rest.charAt(i))
                    || rest.charAt(i) == '-')) {
                i++;
            }
            if (i < rest.length() && rest.charAt(i) == ' ') {
                i++;
            }
        }
        while (i < rest.length()) {
            var c = rest.charAt(i);
            if (c == '\\' && i + 1 < rest.length()) {
                var n = rest.charAt(i + 1);
                if (n == '\'' && i + 3 < rest.length()) {
                    try {
                        sb.append(new String(
                                new byte[] { (byte) Integer.parseInt(
                                        rest.substring(i + 2, i + 4), 16) },
                                forName("windows-1252")));
                    } catch (NumberFormatException e) {
                        // not an escape
                    }
                    i += 4;
                } else if (n == 'u') {
                    var j = i + 2;
                    while (j < rest.length() && (Character.isDigit(
                            rest.charAt(j)) || rest.charAt(j) == '-')) {
                        j++;
                    }
                    try {
                        sb.append((char) Integer.parseInt(
                                rest.substring(i + 2, j)));
                    } catch (NumberFormatException e) {
                        // not an escape
                    }
                    i = j < rest.length() ? j + 1 : j; // skip fallback char
                } else if (n == '\\' || n == '{' || n == '}') {
                    sb.append(n);
                    i += 2;
                } else {
                    // another control word: ignored
                    var j = i + 1;
                    while (j < rest.length()
                            && Character.isLetterOrDigit(rest.charAt(j))) {
                        j++;
                    }
                    i = j < rest.length() && rest.charAt(j) == ' ' ? j + 1 : j;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString().trim();
    }
}
