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

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;

import com.norconex.commons.lang.file.ContentType;

/**
 * Reads the properties of an Office Open XML document (Word, Excel,
 * PowerPoint, Visio): the core properties ({@code dc:creator},
 * {@code cp:lastModifiedBy}, {@code dcterms:created}, ...), the extended
 * ones ({@code Application}, {@code Company}, {@code Pages}, ...) and the
 * custom ones, each under the name the file gave it.
 * <p>
 * The parser renames them ({@code cp:lastModifiedBy} becomes
 * {@code meta:last-author}, {@code Pages} {@code meta:page-count}, ...) and
 * puts the extended and custom ones behind prefixes of its own. What it
 * wrote for them is replaced by what was read.
 * </p>
 */
final class OoxmlNativeMetadataReader implements NativeMetadataReader {

    private static final String PREFIX = "application/vnd.openxmlformats-";

    /** What the parser writes, besides its prefixed keys, from these parts. */
    private static final List<String> TIKA_DERIVED = List.of(
            "dc:title", "dc:subject", "dc:creator", "dc:description",
            "dc:identifier", "dc:language", "dc:publisher",
            "dcterms:created", "dcterms:modified",
            "meta:keyword", "meta:last-author", "meta:print-date",
            "meta:page-count", "meta:slide-count", "meta:paragraph-count",
            "meta:line-count", "meta:word-count", "meta:character-count",
            "meta:character-count-with-spaces");

    private static final List<String> TIKA_PREFIXES =
            List.of("extended-properties:", "cp:", "custom:");

    @Override
    public boolean supports(ContentType contentType) {
        var type = contentType.toBaseTypeString();
        return type.startsWith(PREFIX) || type.endsWith(".macroenabled.12");
    }

    @Override
    public NativeFields read(TikaInputStream in, String charset)
            throws IOException {
        var zip = in.getPath();
        var found = NativeFields.newMap();

        for (var part : List.of("docProps/core.xml", "docProps/app.xml")) {
            var xml = ZipXmlPart.read(zip, part, 2);
            if (xml != null) {
                // lists (titles of parts, ...) have no text of their own
                xml.nodes().forEach(n -> add(found, n.name(), n.text()));
            }
        }
        var custom = ZipXmlPart.read(zip, "docProps/custom.xml", 2);
        if (custom != null) {
            custom.nodes().forEach(n -> add(
                    found, n.attributes().get("name"), n.childText()));
        }
        return found.isEmpty()
                ? NativeFields.none()
                : new NativeFields(found, OoxmlNativeMetadataReader::supersede);
    }

    private static void add(
            Map<String, List<String>> found, String name, String value) {
        if (name != null && !name.isBlank()
                && value != null && !value.isBlank()) {
            NativeFields.add(found, name, value);
        }
    }

    private static void supersede(Metadata tika) {
        for (var name : tika.names()) {
            if (TIKA_PREFIXES.stream().anyMatch(name::startsWith)) {
                tika.remove(name);
            }
        }
        TIKA_DERIVED.forEach(tika::remove);
    }
}
