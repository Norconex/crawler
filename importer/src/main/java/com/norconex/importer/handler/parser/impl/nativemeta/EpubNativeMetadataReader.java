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

import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;

import com.norconex.commons.lang.file.ContentType;

/**
 * Reads the {@code <metadata>} of the package document of an EPUB
 * ({@code dc:title}, {@code dc:creator}, ...), plus its {@code <meta>}
 * elements, which the parser leaves out: those are named by their
 * {@code property} ({@code dcterms:modified}) or {@code name}
 * ({@code calibre:series}) attribute.
 */
final class EpubNativeMetadataReader implements NativeMetadataReader {

    @Override
    public boolean supports(ContentType contentType) {
        return "application/epub+zip".equals(contentType.toBaseTypeString());
    }

    @Override
    public NativeFields read(TikaInputStream in, String charset)
            throws IOException {
        var zip = in.getPath();
        // container > rootfiles > rootfile
        var container = ZipXmlPart.read(zip, "META-INF/container.xml", 3);
        if (container == null) {
            return NativeFields.none();
        }
        String opfPath = null;
        for (var node : container.nodes()) {
            if (node.name().equals("rootfile")) {
                opfPath = node.attributes().get("full-path");
                break;
            }
        }
        if (opfPath == null) {
            return NativeFields.none();
        }
        // package > metadata > entries
        var opf = ZipXmlPart.read(zip, opfPath, 3);
        if (opf == null) {
            return NativeFields.none();
        }
        var found = NativeFields.newMap();
        for (var node : opf.nodes()) {
            // the manifest and the spine sit at the same level
            var name = node.name();
            if (name.equals("meta")) {
                var attrs = node.attributes();
                if (attrs.containsKey("property")) {
                    add(found, attrs.get("property"), node.text());
                } else if (attrs.containsKey("name")) {
                    add(found, attrs.get("name"), attrs.get("content"));
                }
            } else if (name.indexOf(':') > 0 && !node.text().isEmpty()) {
                add(found, name, node.text());
            }
        }
        return found.isEmpty()
                ? NativeFields.none()
                : new NativeFields(found, EpubNativeMetadataReader::supersede);
    }

    private static void add(
            java.util.Map<String, java.util.List<String>> found,
            String name, String value) {
        if (name != null && !name.isBlank()
                && value != null && !value.isBlank()) {
            NativeFields.add(found, name, value);
        }
    }

    private static void supersede(Metadata tika) {
        for (var name : tika.names()) {
            if (name.startsWith("dc:") || name.startsWith("dcterms:")) {
                tika.remove(name);
            }
        }
    }
}
