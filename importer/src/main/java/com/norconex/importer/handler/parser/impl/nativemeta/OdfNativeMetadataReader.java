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
 * Reads {@code meta.xml} of an OpenDocument file (Writer, Calc, Impress,
 * ...): {@code dc:title}, {@code meta:generator},
 * {@code meta:initial-creator}, the attributes of the document statistics
 * ({@code meta:page-count}, ...), the user defined properties (under the
 * name they were given), and so on, each under the name the file used.
 */
final class OdfNativeMetadataReader implements NativeMetadataReader {

    private static final String PREFIX = "application/vnd.oasis.opendocument.";

    private static final List<String> TIKA_DERIVED = List.of(
            "dc:title", "dc:subject", "dc:creator", "dc:description",
            "dc:language", "dcterms:created", "dcterms:modified",
            "editing-cycles", "generator");

    private static final List<String> TIKA_PREFIXES =
            List.of("meta:", "custom:", "odf:");

    @Override
    public boolean supports(ContentType contentType) {
        return contentType.toBaseTypeString().startsWith(PREFIX);
    }

    @Override
    public NativeFields read(TikaInputStream in, String charset)
            throws IOException {
        // root > office:meta > properties
        var part = ZipXmlPart.read(in.getPath(), "meta.xml", 3);
        if (part == null) {
            return NativeFields.none();
        }
        var found = NativeFields.newMap();
        add(found, "office:version",
                part.rootAttributes().get("office:version"));
        for (var node : part.nodes()) {
            if (node.name().equals("meta:user-defined")) {
                add(found, node.attributes().get("meta:name"), node.text());
            } else if (node.text().isEmpty()) {
                // e.g. meta:document-statistic: values are attributes
                node.attributes().forEach((k, v) -> add(found, k, v));
            } else {
                add(found, node.name(), node.text());
            }
        }
        return found.isEmpty()
                ? NativeFields.none()
                : new NativeFields(found, OdfNativeMetadataReader::supersede);
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
