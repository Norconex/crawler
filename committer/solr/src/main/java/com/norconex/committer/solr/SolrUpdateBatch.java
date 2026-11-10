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
package com.norconex.committer.solr;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.norconex.commons.lang.map.Properties;

/**
 * Builds the body of a Solr JSON update request: a series of "add" and
 * "delete" commands, kept in the order they were given. Solr accepts
 * repeating command names within the same JSON object, and executes them
 * in order.
 */
final class SolrUpdateBatch {

    private final StringBuilder json = new StringBuilder("{");
    private int size;

    /**
     * Adds a document to the batch. Values of a multi-valued field are
     * sent as an array.
     * @param fields document fields
     */
    void add(Properties fields) {
        startCommand("add");
        json.append("{\"doc\":{");
        var firstField = true;
        for (var name : fields.keySet()) {
            var values = fields.getStrings(name).stream()
                    .filter(Objects::nonNull)
                    .toList();
            if (values.isEmpty()) {
                continue;
            }
            if (!firstField) {
                json.append(',');
            }
            firstField = false;
            appendString(name);
            json.append(':');
            if (values.size() == 1) {
                appendString(values.get(0));
            } else {
                json.append('[');
                for (var i = 0; i < values.size(); i++) {
                    if (i > 0) {
                        json.append(',');
                    }
                    appendString(values.get(i));
                }
                json.append(']');
            }
        }
        json.append("}}");
    }

    /**
     * Adds a delete-by-id operation to the batch.
     * @param id the unique id of the document to delete
     */
    void delete(String id) {
        startCommand("delete");
        json.append("{\"id\":");
        appendString(id);
        json.append('}');
    }

    /**
     * Gets the number of operations in this batch.
     * @return number of operations
     */
    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    byte[] toBytes() {
        return (json + "}").getBytes(StandardCharsets.UTF_8);
    }

    private void startCommand(String name) {
        if (size++ > 0) {
            json.append(',');
        }
        json.append('"').append(name).append("\":");
    }

    private void appendString(String str) {
        json.append('"');
        for (var i = 0; i < str.length(); i++) {
            var ch = str.charAt(i);
            switch (ch) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        json.append(String.format("\\u%04x", (int) ch));
                    } else if (Character.isSurrogate(ch)
                            && !isPairedSurrogate(str, i)) {
                        // lone surrogates cannot be encoded as UTF-8
                        json.append((char) 0xFFFD);
                    } else {
                        json.append(ch);
                    }
                }
            }
        }
        json.append('"');
    }

    // true if the char at i is part of a well-formed surrogate pair
    private static boolean isPairedSurrogate(String str, int i) {
        if (Character.isHighSurrogate(str.charAt(i))) {
            return i + 1 < str.length()
                    && Character.isLowSurrogate(str.charAt(i + 1));
        }
        return i > 0 && Character.isHighSurrogate(str.charAt(i - 1));
    }
}
