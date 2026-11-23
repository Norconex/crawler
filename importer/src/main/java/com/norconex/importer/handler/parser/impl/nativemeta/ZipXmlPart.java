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
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads an XML part of a zip package (such as {@code docProps/core.xml}),
 * keeping the names as written, prefix included.
 */
final class ZipXmlPart {

    /** No part of metadata is worth more than this. */
    private static final int MAX_PART_BYTES = 1024 * 1024;

    /**
     * An element holding a property.
     * @param name its name as written, with the prefix
     * @param attributes its attributes, by name as written
     * @param text its own text, trimmed, empty if none
     * @param childText the text of its first child element, trimmed, or empty
     */
    record Node(
            String name,
            Map<String, String> attributes,
            String text,
            String childText) {
    }

    /**
     * The root element attributes and the nodes at a given level.
     * @param rootAttributes attributes of the root element
     * @param nodes the elements at the requested level
     */
    record Part(Map<String, String> rootAttributes, List<Node> nodes) {
    }

    private ZipXmlPart() {
    }

    /**
     * Reads a part of a zip file.
     * @param zip the file
     * @param entryName the part path
     * @param level depth of the elements that hold properties: 2 for
     *      children of the root element, 3 for grandchildren
     * @return the part, or {@code null} if missing or unreadable
     */
    static Part read(Path zip, String entryName, int level) {
        try (var file = new ZipFile(zip.toFile())) {
            var entry = file.getEntry(entryName);
            if (entry == null || entry.getSize() > MAX_PART_BYTES) {
                return null;
            }
            try (var in = file.getInputStream(entry)) {
                return parse(in, level);
            }
        } catch (IOException | XMLStreamException | RuntimeException e) {
            return null;
        }
    }

    private static Part parse(InputStream in, int level)
            throws XMLStreamException {
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(
                XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        var reader = factory.createXMLStreamReader(in);
        var rootAttrs = new LinkedHashMap<String, String>();
        var nodes = new ArrayList<Node>();

        var depth = 0;
        String name = null;
        Map<String, String> attrs = null;
        var text = new StringBuilder();
        var childText = new StringBuilder();
        var childSeen = false;
        var inFirstChild = false;
        var firstChildDone = false;

        while (reader.hasNext()) {
            switch (reader.next()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    depth++;
                    if (depth == 1) {
                        attrsOf(reader, rootAttrs);
                    } else if (depth == level) {
                        name = qName(reader);
                        attrs = new LinkedHashMap<>();
                        attrsOf(reader, attrs);
                        text.setLength(0);
                        childText.setLength(0);
                        childSeen = false;
                        firstChildDone = false;
                    } else if (depth == level + 1 && !childSeen) {
                        childSeen = true;
                        inFirstChild = true;
                    }
                }
                case XMLStreamConstants.CHARACTERS,
                        XMLStreamConstants.CDATA -> {
                    if (depth == level) {
                        text.append(reader.getText());
                    } else if (depth == level + 1 && inFirstChild
                            && !firstChildDone) {
                        childText.append(reader.getText());
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    if (depth == level + 1 && inFirstChild) {
                        inFirstChild = false;
                        firstChildDone = true;
                    } else if (depth == level) {
                        nodes.add(new Node(name, attrs,
                                text.toString().trim(),
                                childText.toString().trim()));
                    }
                    depth--;
                }
                default -> {
                }
            }
        }
        reader.close();
        return new Part(rootAttrs, nodes);
    }

    private static String qName(XMLStreamReader r) {
        var prefix = r.getPrefix();
        return prefix == null || prefix.isEmpty()
                ? r.getLocalName()
                : prefix + ":" + r.getLocalName();
    }

    private static void attrsOf(XMLStreamReader r, Map<String, String> into) {
        for (var i = 0; i < r.getAttributeCount(); i++) {
            var prefix = r.getAttributePrefix(i);
            var n = prefix == null || prefix.isEmpty()
                    ? r.getAttributeLocalName(i)
                    : prefix + ":" + r.getAttributeLocalName(i);
            into.put(n, r.getAttributeValue(i));
        }
    }
}
