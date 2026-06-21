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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.commons.lang.map.PropertySetter;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>Define and add constant values to documents.  To add multiple constant
 * values under the same constant name, repeat the constant entry with a
 * different value.
 * </p>
 * <h2>Storing values in an existing field</h2>
 * <p>
 * If a target field with the same name already exists for a document,
 * values will be added to the end of the existing value list.
 * It is possible to change this default behavior
 * with {@link #setOnSet(PropertySetter)}.
 * </p>
 * <p>Can be used both as a pre-parse or post-parse handler.</p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.ConstantTagger"
 *     {@nx.include com.norconex.commons.lang.map.PropertySetter#attributes}>
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <!-- multiple constant tags allowed -->
 *   <constant name="CONSTANT_NAME">Constant Value</constant>
 *
 * </handler>
 * }
 *
 * {@nx.xml.example
 *  <handler class="ConstantTagger">
 *    <constant name="source">web</constant>
 *  </handler>
 * }
 * <p>
 * The above example adds a constant to incoming documents to identify they
 * were web documents.
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class ConstantTransformerConfig {

    private final List<Constant> constants = new ArrayList<>();
    /**
     * Default property setter when a constant does not specify one.
     */
    private PropertySetter onSet;

    /**
     * Get constants to be added as metadata fields.
     * @return list of constants
     */
    public List<Constant> getConstants() {
        return Collections.unmodifiableList(constants);
    }

    /**
     * Set constants to be added as metadata fields.
     * @param constants list of constants
     * @return this instance
     */
    public ConstantTransformerConfig setConstants(List<Constant> constants) {
        CollectionUtil.setAll(this.constants, constants);
        return this;
    }
}
