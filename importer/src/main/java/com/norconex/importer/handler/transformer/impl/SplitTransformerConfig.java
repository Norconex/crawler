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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.norconex.commons.lang.bean.jackson.JsonXmlCollection;
import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.commons.lang.map.PropertySetter;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>Splits an existing metadata value into multiple values based on a given
 * value separator (the separator gets discarded).  The "toField" argument
 * is optional (the same field will be used to store the splits if no
 * "toField" is specified"). Duplicates are removed.</p>
 * <p>Can be used both as a pre-parse (metadata or text content) or
 * post-parse handler.</p>
 * <p>
 * If no "fieldMatcher" expression is specified, the document content will be
 * used.  If the "fieldMatcher" matches more than one field, they will all
 * be split and stored in the same multi-value metadata field.
 * </p>
 * <h2>Storing values in an existing field</h2>
 * <p>
 * If a target field with the same name already exists for a document,
 * values will be added to the end of the existing value list.
 * It is possible to change this default behavior by supplying a
 * {@link PropertySetter}.
 * </p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.SplitTagger"
 *     {@nx.include com.norconex.importer.handler.tagger.AbstractCharStreamTagger#attributes}>
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <!-- multiple split tags allowed -->
 *   <split
 *       toField="targetFieldName"
 *       {@nx.include com.norconex.commons.lang.map.PropertySetter#attributes}>
 *     <fieldMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *       (one or more matching fields to split)
 *     </fieldMatcher>
 *     <separator regex="[false|true]">(separator value)</separator>
 *   </split>
 *
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="SplitTagger">
 *   <split>
 *     <fieldMatcher>myField</fieldMatcher>
 *     <separator regex="true">\s*,\s*</separator>
 *   </split>
 * </handler>
 * }
 * <p>
 * The above example splits a single value field holding a comma-separated
 * list into multiple values.
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class SplitTransformerConfig extends BaseDocHandlerConfig {

    @JsonXmlCollection(entryName = "op")
    private final List<SplitOperation> operations = new ArrayList<>();

    public SplitTransformerConfig setOperations(
            List<SplitOperation> operations) {
        CollectionUtil.setAll(this.operations, operations);
        return this;
    }

    public List<SplitOperation> getOperations() {
        return Collections.unmodifiableList(operations);
    }
}
