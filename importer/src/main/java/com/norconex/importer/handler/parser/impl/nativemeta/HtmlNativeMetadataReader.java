/* Copyright 2023-2026 Norconex Inc.
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.jsoup.Jsoup;

import com.norconex.commons.lang.file.ContentType;

/**
 * Reads the {@code <meta>} tags of the head of an HTML page, under the
 * names the page gave them ({@code name}, {@code http-equiv} or
 * {@code property}, in that order of precedence, as the parser does).
 * <p>
 * The HTML parser renames some of these ({@code author} becomes
 * {@code dc:creator}, for instance), merges others, and puts a prefix in
 * front of the rest. It does so while it reads the page, so what it leaves
 * cannot be told apart from what it works out itself, such as the text of
 * the {@code <title>} element. Reading the tags separately is the only way
 * to tell what the page said.
 * </p>
 */
final class HtmlNativeMetadataReader implements NativeMetadataReader {

    /** Same types as Tika's HTML parser. */
    private static final Set<String> TYPES = Set.of(
            "text/html",
            "application/xhtml+xml",
            "application/vnd.wap.xhtml+xml",
            "application/x-asp");

    /** The head of a page is read up to this size, or to its end. */
    private static final int MAX_HEAD_BYTES = 1024 * 1024;

    /** The parser's own prefix for names it did not map. */
    private static final String TIKA_PREFIX = "html:";

    /**
     * Names the HTML parser maps to its own properties, by lower case name,
     * with the keys it writes them to.
     */
    private static final Map<String, List<String>> TIKA_MAPPED = Map.of(
            "author", List.of("dc:creator"),
            "title", List.of("dc:title"),
            "subject", List.of("dc:subject"),
            "keywords", List.of("meta:keyword", "dc:subject"),
            "description", List.of("dc:description"));

    /** What the parser writes for an {@code ICBM} tag. */
    private static final List<String> TIKA_ICBM =
            List.of("ICBM", "geo:lat", "geo:long");

    @Override
    public boolean supports(ContentType contentType) {
        return TYPES.contains(contentType.toBaseTypeString());
    }

    @Override
    public NativeFields read(TikaInputStream in, String charset)
            throws IOException {
        var bytes = NativeMetadata.peek(in, MAX_HEAD_BYTES);
        if (bytes == null || bytes.length == 0) {
            return NativeFields.none();
        }
        var head = Arrays.copyOf(bytes, endOfHead(bytes));
        var doc = Jsoup.parse(
                new ByteArrayInputStream(head), toCharset(charset), "");

        var found = NativeFields.newMap();

        // The <title> element has no tag naming a field, so it takes its
        // own name. It comes first; the parser reads only the first one.
        var titleElement = doc.head().selectFirst("title");
        var elementText = titleElement == null
                ? ""
                : titleElement.wholeText().trim();
        if (!elementText.isEmpty()) {
            NativeFields.add(found, "title", elementText);
        }

        for (var meta : doc.head().select("meta")) {
            if (!meta.hasAttr("content")) {
                continue;
            }
            String name;
            if (meta.hasAttr("http-equiv")) {
                name = meta.attr("http-equiv");
            } else if (meta.hasAttr("name")) {
                name = meta.attr("name");
            } else if (meta.hasAttr("property")) {
                name = meta.attr("property");
            } else {
                continue;
            }
            var value = meta.attr("content");
            if (name.isBlank() || value.isBlank()) {
                continue;
            }
            NativeFields.add(found, name, value);
        }
        if (found.isEmpty()) {
            return NativeFields.none();
        }
        var hasTitleElement = titleElement != null;
        return new NativeFields(found,
                tika -> supersede(tika, found, hasTitleElement));
    }

    /**
     * Takes out of the parser's metadata what stands for what was read.
     * With a {@code <title>} element, the parser's {@code dc:title} is that
     * element's text, whatever the order of tags and tags named
     * {@code title}, which now come under the names given. Without one,
     * it holds the values of tags named {@code title}.
     */
    private static void supersede(
            Metadata tika,
            Map<String, List<String>> found,
            boolean hasTitleElement) {
        // Everything the parser did not map, under its prefix.
        for (var name : tika.names()) {
            if (name.startsWith(TIKA_PREFIX)
                    && !name.equals(TIKA_PREFIX + "scriptSrc")) {
                tika.remove(name);
            }
        }
        if (hasTitleElement) {
            tika.remove("dc:title");
        }
        // What it mapped or derived, of the tags we read.
        found.forEach((name, values) -> {
            var lcName = name.toLowerCase(Locale.ROOT);
            if (lcName.equals("icbm")) {
                TIKA_ICBM.forEach(tika::remove);
            }
            var keys = TIKA_MAPPED.get(lcName);
            if (keys != null) {
                keys.forEach(key -> removeValues(tika, key, values));
            }
        });
    }

    /** Takes values out of a key, leaving the others (e.g. from the title). */
    private static void removeValues(
            Metadata tika, String key, List<String> values) {
        var current = tika.getValues(key);
        if (current.length == 0) {
            return;
        }
        var kept = Arrays.stream(current)
                .filter(v -> !values.contains(v))
                .toList();
        if (kept.size() == current.length) {
            return;
        }
        tika.remove(key);
        kept.forEach(v -> tika.add(key, v));
    }

    /** Where the head ends: the end tag, or the start of the body. */
    private static int endOfHead(byte[] bytes) {
        var text = new String(bytes, ISO_8859_1).toLowerCase(Locale.ROOT);
        var end = bytes.length;
        for (var marker : new String[] { "</head", "<body", "<frameset" }) {
            var idx = text.indexOf(marker);
            if (idx >= 0 && idx < end) {
                end = idx;
            }
        }
        return end;
    }

    private static String toCharset(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Charset.forName(name).name();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
