/* Copyright 2010-2024 Norconex Inc.
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
package com.norconex.importer.handler.transformer.impl;

import com.norconex.importer.handler.BaseDocHandlerConfig;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.norconex.commons.lang.bean.jackson.JsonXmlCollection;
import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.commons.lang.io.TextReader;
import com.norconex.commons.lang.map.PropertySetter;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>Extracts and add values found between a matching start and
 * end strings to a document metadata field.
 * The matching string end-points are defined in pairs and multiple ones
 * can be specified at once. The field specified for a pair of end-points
 * is considered a multi-value field.</p>
 * <p>
 * If "fieldMatcher" is specified, it will use content from matching fields and
 * storing all text extracted into the target field, multi-value.
 * Else, the document content is used.
 * </p>
 *
 * <h2>Storing values in an existing field</h2>
 * <p>
 * If a target field with the same name already exists for a document,
 * values will be added to the end of the existing value list.
 * It is possible to change this default behavior by supplying a
 * {@link PropertySetter}.
 * </p>
 * <p>
 * This class can be used as a pre-parsing handler on text documents only
 * or a post-parsing handler.
 * </p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.TextBetweenTagger"
 *     {@nx.include com.norconex.importer.handler.tagger.AbstractStringTagger#attributes}>
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <!-- multiple textBetween tags allowed -->
 *   <textBetween
 *       toField="(target field name)"
 *       inclusive="[false|true]"
 *       {@nx.include com.norconex.commons.lang.map.PropertySetter#attributes}>
 *     <fieldMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *       (optional expression matching fields to perform extraction on)
 *     </fieldMatcher>
 *     <startMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *       (expression matching "left" delimiter)
 *     </startMatcher>
 *     <endMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *       (expression matching "right" delimiter)
 *     </endMatcher>
 *   </textBetween>
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="TextBetweenTransformer">
 *   <textBetween toField="content">
 *     <startMatcher>OPEN</startMatcher>
 *     <endMatcher>CLOSE</endMatcher>
 *   </textBetween>
 * </handler>
 * }
 * <p>
 * The above example extract the content between "OPEN" and
 * "CLOSE" strings, excluding these strings, and store it in a "content"
 * field.
 * </p>
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class TextBetweenTransformerConfig extends BaseDocHandlerConfig {

    private int maxReadSize = TextReader.DEFAULT_MAX_READ_SIZE;
    private Charset sourceCharset;

    @JsonXmlCollection(entryName = "op")
    private final List<TextBetweenOperation> operations = new ArrayList<>();

    public List<TextBetweenOperation> getOperations() {
        return Collections.unmodifiableList(operations);
    }

    public TextBetweenTransformerConfig setOperations(
            List<TextBetweenOperation> operations) {
        CollectionUtil.setAll(this.operations, operations);
        return this;
    }
}
