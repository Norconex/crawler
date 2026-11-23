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
package com.norconex.committer.core.acl;

import java.util.Optional;

import com.norconex.commons.lang.map.Properties;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an {@link Acl} as the JSON text documents carry, and reads it back.
 * The text is canonical: the same permissions always give the same text
 * (entries sorted, map keys sorted), so a crawler can tell whether a
 * document's permissions changed by comparing it.
 */
public final class AclCodec {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            // An unknown property may come from a newer version of the shape:
            // the version check decides, not the parser.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // An unknown enum value (a new kind or access) is not read as
            // null or as something else: reading fails (Jackson's default,
            // kept explicitly), so the document is rejected.
            .disable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
            .disable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
            .build();

    private AclCodec() {
    }

    /**
     * @param acl the ACL
     * @return its canonical JSON text
     */
    public static String toJson(Acl acl) {
        try {
            return MAPPER.writeValueAsString(acl);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Could not write ACL.", e);
        }
    }

    /**
     * @param json JSON text written by {@link #toJson(Acl)}
     * @return the ACL
     * @throws IllegalArgumentException if the text is not a readable ACL
     */
    public static Acl fromJson(String json) {
        try {
            return MAPPER.readValue(json, Acl.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "not a readable ACL: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Reads a document's ACL.
     * @param metadata the document's metadata
     * @return the ACL, or empty if the document has none
     * @throws IllegalArgumentException if the document's ACL is not readable,
     *     or it has more than one
     */
    public static Optional<Acl> read(Properties metadata) {
        var values = metadata.getStrings(Acl.FIELD);
        if (values.isEmpty()) {
            return Optional.empty();
        }
        if (values.size() > 1) {
            throw new IllegalArgumentException("not a readable ACL:the "
                    + Acl.FIELD + " field holds " + values.size()
                    + " values instead of one");
        }
        return Optional.of(fromJson(values.get(0)));
    }

    /**
     * Sets a document's ACL, replacing any it had.
     * @param metadata the document's metadata
     * @param acl the ACL
     */
    public static void write(Properties metadata, Acl acl) {
        metadata.set(Acl.FIELD, toJson(acl));
    }
}
