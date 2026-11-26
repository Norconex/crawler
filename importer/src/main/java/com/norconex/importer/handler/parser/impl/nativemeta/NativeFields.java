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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.tika.metadata.Metadata;

import com.norconex.commons.lang.map.Properties;

/**
 * Metadata fields as a document's author published them, in the order
 * found.
 */
public final class NativeFields {

    private static final NativeFields NONE =
            new NativeFields(Map.of(), md -> {});

    private final Map<String, List<String>> fields;
    private final Consumer<Metadata> supersede;

    NativeFields(
            Map<String, List<String>> fields, Consumer<Metadata> supersede) {
        this.fields = fields;
        this.supersede = supersede;
    }

    /** @return nothing found */
    public static NativeFields none() {
        return NONE;
    }

    static Map<String, List<String>> newMap() {
        return new LinkedHashMap<>();
    }

    static void add(Map<String, List<String>> map, String name, String value) {
        map.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
    }

    /** @return the fields, by name as found */
    public Map<String, List<String>> asMap() {
        return fields;
    }

    /** @return whether there is nothing to apply */
    public boolean isEmpty() {
        return fields.isEmpty();
    }

    /**
     * Takes out of the parser's metadata what stands for these fields.
     * @param parserMetadata the parser metadata, after parsing
     */
    public void supersede(Metadata parserMetadata) {
        supersede.accept(parserMetadata);
    }

    /**
     * Adds the fields to a document's metadata, exactly as found.
     * @param target the document metadata
     */
    public void addTo(Properties target) {
        fields.forEach((name, values) -> {
            for (var value : values) {
                if (!target.getStrings(name).contains(value)) {
                    target.add(name, value);
                }
            }
        });
    }
}
