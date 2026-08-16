/* Copyright 2023-2024 Norconex Inc.
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

import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.commons.lang.text.TextMatcher;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@SuppressWarnings("javadoc")
public class SplitOperation {

    private final TextMatcher fieldMatcher = new TextMatcher();

    private String toField;

    /**
     * The property setter to use when a value is set.
     * @param onSet property setter
     * @return property setter
     */
    private PropertySetter onSet;

    private String separator;

    /**
     * Whether the separator value is a regular expression.
     * @param separatorRegex <code>true</code> if a regular expression.
     * @return <code>true</code> if a regular expression.
     */
    private boolean separatorRegex;

    /**
     * Gets field matcher for fields to split.
     * @return field matcher
     */
    public TextMatcher getFieldMatcher() {
        return fieldMatcher;
    }

    /**
     * Sets the field matcher for fields to split.
     * @param fieldMatcher field matcher
     */
    public SplitOperation setFieldMatcher(TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }
}
