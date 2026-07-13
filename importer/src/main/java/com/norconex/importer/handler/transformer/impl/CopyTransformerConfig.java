/* Copyright 2014-2024 Norconex Inc.
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
 * <p>Copies metadata fields.</p>
 *
 * <h2>Storing values in an existing field</h2>
 * <p>
 * If a target field with the same name already exists for a document,
 * values will be added to the end of the existing value list.
 * It is possible to change this default behavior by supplying a
 * {@link PropertySetter}.
 * </p>
 *
 * <p>Can be used both as a pre-parse or post-parse handler.</p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.CopyTagger">
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <!-- multiple copy tags allowed -->
 *   <copy toField="(to field)"
 *       {@nx.include com.norconex.commons.lang.map.PropertySetter#attributes}>
 *     <fieldMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *       (one or more matching fields to copy)
 *     </fieldMatcher>
 *   </copy>
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="CopyTagger">
 *   <copy toField="author" onSet="append">
 *     <fieldMatcher method="regex">(creator|publisher)</fieldMatcher>
 *   </copy>
 * </handler>
 * }
 * <p>
 * Copies the value of a "creator" and "publisher" fields into an "author"
 * field, adding to any existing values in the "author" field.
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class CopyTransformerConfig extends BaseDocHandlerConfig {

    @JsonXmlCollection(entryName = "op")
    private final List<CopyOperation> operations = new ArrayList<>();

    /**
     * Default property setter when an operation does not specify one.
     */
    private PropertySetter onSet;

    public CopyTransformerConfig setOperations(
            List<CopyOperation> operations) {
        CollectionUtil.setAll(this.operations, operations);
        return this;
    }

    public List<CopyOperation> getOperations() {
        return Collections.unmodifiableList(operations);
    }
}
