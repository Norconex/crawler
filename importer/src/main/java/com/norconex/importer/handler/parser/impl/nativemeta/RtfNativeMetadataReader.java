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

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
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
    private static final Pattern GROUP_START =
            Pattern.compile("^\\\\([a-z]+)(.*)$", Pattern.DOTALL);
    /** The parameter and space following a control word. */
    private static final Pattern PARAMETER = Pattern.compile("^-?\\d* ?");
    private static final Pattern TOKEN = Pattern.compile(
            "\\\\'([0-9a-fA-F]{2})|\\\\u(-?\\d+).?|\\\\([\\\\{}])"
                    + "|\\\\[a-zA-Z]+-?\\d* ?|\\\\.|.",
            Pattern.DOTALL);
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    @Override
    public boolean supports(ContentType contentType) {
        return TYPES.contains(contentType.toBaseTypeString());
    }

    @Override
    public NativeFields read(TikaInputStream in, String charset)
            throws IOException {
        var bytes = NativeMetadata.peek(in, MAX_BYTES);
        var rtf = bytes == null ? "" : new String(bytes, ISO_8859_1);
        var start = rtf.indexOf("{\\info");
        if (start < 0) {
            return NativeFields.none();
        }
        var end = endOfGroup(rtf, start);
        var info = rtf.substring(start + "{\\info".length(), end);

        var found = NativeFields.newMap();
        var open = info.indexOf('{');
        while (open >= 0) {
            var close = endOfGroup(info, open);
            addGroup(found, info.substring(open + 1, close));
            open = info.indexOf('{', close + 1);
        }
        return found.isEmpty()
                ? NativeFields.none()
                : new NativeFields(found, OoxmlNativeMetadataReader::supersede);
    }

    private static void addGroup(Map<String, List<String>> found,
            String group) {
        var m = GROUP_START.matcher(group);
        if (!m.matches()) {
            return;
        }
        var rest = m.group(2);
        var value = DATE_PART.matcher(rest).find() ? date(rest) : text(rest);
        if (!value.isBlank()) {
            NativeFields.add(found, m.group(1), value);
        }
    }

    private static int endOfGroup(String s, int open) {
        var depth = 0;
        var i = open;
        while (i < s.length()) {
            var c = s.charAt(i);
            if (c == '\\') {
                i++; // escaped brace or control symbol
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
            i++;
        }
        return s.length();
    }

    private static String date(String rest) {
        var parts = new HashMap<String, Integer>();
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
        var m = TOKEN.matcher(PARAMETER.matcher(rest).replaceFirst(""));
        while (m.find()) {
            if (m.group(1) != null) {
                sb.append(new String(new byte[] {
                        (byte) Integer.parseInt(m.group(1), 16) },
                        WINDOWS_1252));
            } else if (m.group(2) != null) {
                sb.append((char) Integer.parseInt(m.group(2)));
            } else if (m.group(3) != null) {
                sb.append(m.group(3));
            } else if (!m.group().startsWith("\\")) {
                // anything else starting with a backslash is ignored
                sb.append(m.group());
            }
        }
        return sb.toString().trim();
    }
}
