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
 * <h2>Truncating (or clearing) the document content</h2>
 * <p>
 * When no <code>fieldMatcher</code> is specified, this handler truncates the
 * document content (body) instead of a field. Setting <code>maxLength</code>
 * to <code>0</code> with no <code>fieldMatcher</code> effectively clears the
 * document content entirely, while leaving its metadata untouched. Pair it
 * with <code>toField</code> to first copy the content elsewhere (subject to
 * the same <code>maxLength</code>) before it is cleared - use a large
 * <code>maxLength</code> if the copy should not be truncated.
 * </p>
 * <p>
 * Can be used both as a pre-parse or post-parse handler.
 * </p>
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
 * @see <a href="https://crawler.norconex.com/docs/reference/importer/TruncateTransformer">
 *      TruncateTransformer configuration reference</a>
 */
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
     * Gets the field matcher for fields to truncate. When unset (the
     * default), the document content (body) is truncated instead.
     * @return field matcher
     */
    public TextMatcher getFieldMatcher() {
        return fieldMatcher;
    }

    /**
     * Sets the field matcher for fields to truncate. When unset (the
     * default), the document content (body) is truncated instead.
     * @param fieldMatcher field matcher
     */
    public TruncateTransformerConfig setFieldMatcher(
            TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }
}
