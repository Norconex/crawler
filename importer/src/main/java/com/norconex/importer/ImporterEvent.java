/* Copyright 2020-2025 Norconex Inc.
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
package com.norconex.importer;

import com.norconex.commons.lang.event.Event;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.handler.parser.ParseState;

import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * An Importer event.
 */
@Data
@Setter(value = AccessLevel.NONE)
@SuperBuilder
@EqualsAndHashCode(callSuper = true)
public class ImporterEvent extends Event {

    private static final long serialVersionUID = 1L;

    public static final String IMPORTER_INIT_BEGIN = "IMPORTER_INIT_BEGIN";
    public static final String IMPORTER_INIT_END = "IMPORTER_INIT_END";
    public static final String IMPORTER_HANDLER_BEGIN =
            "IMPORTER_HANDLER_BEGIN";
    public static final String IMPORTER_HANDLER_END = "IMPORTER_HANDLER_END";
    public static final String IMPORTER_HANDLER_ERROR =
            "IMPORTER_HANDLER_ERROR";
    public static final String IMPORTER_HANDLER_CONDITION_TRUE =
            "IMPORTER_HANDLER_CONDITION_TRUE";
    public static final String IMPORTER_HANDLER_CONDITION_FALSE =
            "IMPORTER_HANDLER_CONDITION_FALSE";
    public static final String IMPORTER_PARSER_BEGIN = "IMPORTER_PARSER_BEGIN";
    public static final String IMPORTER_PARSER_END = "IMPORTER_PARSER_END";
    public static final String IMPORTER_PARSER_ERROR = "IMPORTER_PARSER_ERROR";

    /**
     * Gets the document parse state (never {@code null}).
     * @return parse state
     */
    private final ParseState parseState;
    /**
     * Gets the document associated with this event, if applicable.
     * @return a document
     */
    private final transient Doc document;

    /**
     * Gets whether this document was parsed. Convenience method equivalent
     * to <code>return getParseState() == ParseState.POST</code>.
     * @return <code>true</code> if the document was parsed
     */
    public boolean isParsed() {
        return ParseState.isPost(parseState);
    }

    /**
     * A string representation of this event.
     */
    @Override
    public String toString() {
        var b = new StringBuilder(super.toString())
                .append(" - ")
                .append(getDocument().getReference()) // doc is never null
                .append(" - Parsed: ").append(isParsed());
        if (getSource() != null) {
            b.append(" - ").append(getSource().toString());
        }
        return b.toString();
    }
}
