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
 * <p>Given a separator, split a field string into multiple segments
 * representing each node of a hierarchical branch. This is useful
 * when faceting, to find out how many documents fall under each
 * node of a hierarchy. For example, take this hierarchical string:</p>
 * <pre>
 *   /vegetable/potato/sweet
 * </pre>
 * <p>We specify a slash (/) separator and it will produce the following entries
 * in the specified document metadata field:</p>
 *
 * <pre>
 *   /vegetable
 *   /vegetable/potato
 *   /vegetable/potato/sweet
 * </pre>
 * <p>
 * If no target field is specified (<code>toField</code>) the
 * source field (<code>fromField</code>) will be used to store the resulting
 * values. The same applies to the source and target hierarchy separators
 * (<code>fromSeparator</code> and <code>toSeparator</code>).
 * </p>
 * <p>
 * You can "keepEmptySegments", as well as specify
 * whether the "fromSeparator" is a regular expression. When using regular
 * expression without a "toSeparator", the text matching the expression is
 * kept as is and thus can be different for each segment.
 * </p>
 * <h2>Storing values in an existing field</h2>
 * <p>
 * If a target field with the same name already exists for a document,
 * values will be added to the end of the existing value list.
 * It is possible to change this default behavior by supplying a
 * {@link PropertySetter}.
 * </p>
 * <p>
 * Can be used both as a pre-parse or post-parse handler.
 * </p>
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.HierarchyTagger">
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <!-- multiple hierarchy tags allowed -->
 *   <hierarchy fromField="(from field)"
 *       toField="(optional to field)"
 *       fromSeparator="(original separator)"
 *       toSeparator="(optional new separator)"
 *       {@nx.include com.norconex.commons.lang.map.PropertySetter#attributes}
 *       regex="[false|true]"
 *       keepEmptySegments="[false|true]" />
 * </handler>
 * }
 *
 * {@nx.xml.example
 *  <handler class="HierarchyTagger">
 *      <hierarchy fromField="vegetable" toField="vegetableHierarchy"
 *                 fromSeparator="/"/>
 *  </handler>
 * }
 * <p>
 * The above will expand a slash-separated vegetable hierarchy found in a
 * "vegetable" field into a "vegetableHierarchy" field.
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class HierarchyTransformerConfig extends BaseDocHandlerConfig {

    /**
     * The list of operations to perform.
     */
    @JsonXmlCollection(entryName = "op")
    private final List<HierarchyOperation> operations = new ArrayList<>();

    public HierarchyTransformerConfig setOperations(
            List<HierarchyOperation> operations) {
        CollectionUtil.setAll(this.operations, operations);
        return this;
    }

    public List<HierarchyOperation> getOperations() {
        return Collections.unmodifiableList(operations);
    }
}
