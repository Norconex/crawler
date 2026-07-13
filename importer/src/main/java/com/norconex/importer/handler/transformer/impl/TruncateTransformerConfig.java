/* Copyright 2017-2024 Norconex Inc.
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

import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.commons.lang.text.TextMatcher;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * Truncates a <code>fromField</code> value(s) and optionally replace truncated
 * portion by a hash value to help ensure uniqueness (not 100% guaranteed to
 * be collision-free).  If the field to truncate has multiple values, all
 * values will be subject to truncation. You can store the value(s), truncated
 * or not, in another target field.
 * </p>
 * <h2>Storing values in an existing field</h2>
 * <p>
 * If a target field with the same name already exists for a document,
 * values will be added to the end of the existing value list.
 * It is possible to change this default behavior by supplying a
 * {@link PropertySetter}.
 * </p>
 * <p>
 * The <code>maxLength</code> is guaranteed to be respected. This means any
 * appended hash code and suffix will fit within the <code>maxLength</code>.
 * </p>
 * <p>
 * Can be used both as a pre-parse or post-parse handler.
 * </p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.TruncateTagger"
 *     maxLength="(maximum length)"
 *     toField="(optional target field where to store the truncated value)"
 *     {@nx.include com.norconex.commons.lang.map.PropertySetter#attributes}
 *     appendHash="[false|true]"
 *     suffix="(value to append after truncation. Goes before hash if one.)">
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <fieldMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *     (one or more matching fields to have their values truncated)
 *   </fieldMatcher>

 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="TruncateTagger"
 *     maxLength="50"
 *     appendHash="true"
 *     suffix="!">
 *   <fieldMatcher>myField</fieldMatcher>
 * </handler>
 * }
 *
 * <p>
 * Assuming this "myField" value...
 * </p>
 * <pre>    Please truncate me before you start thinking I am too long.</pre>
 * <p>
 * ...the above example will truncate it to...
 * </p>
 * <pre>    Please truncate me before you start thi!0996700004</pre>
 *
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class TruncateTransformerConfig extends BaseDocHandlerConfig {

    private final TextMatcher fieldMatcher = new TextMatcher();
    private int maxLength;
    private String toField;
    /**
     * The property setter to use when a value is set.
     * @param onSet property setter
     * @return property setter
     */
    private PropertySetter onSet;
    /**
     * Whether to apply the original string hash code at the end of the
     * truncation to help ensure uniqueness (no guarantee).
     */
    private boolean appendHash;
    private String suffix;

    /**
     * Gets field matcher for fields to truncate.
     * @return field matcher
     */
    public TextMatcher getFieldMatcher() {
        return fieldMatcher;
    }

    /**
     * Sets the field matcher for fields to truncate.
     * @param fieldMatcher field matcher
     */
    public TruncateTransformerConfig setFieldMatcher(
            TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }
}
