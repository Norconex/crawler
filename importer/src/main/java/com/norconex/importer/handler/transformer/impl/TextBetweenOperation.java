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
public class TextBetweenOperation {
    private final TextMatcher fieldMatcher = new TextMatcher();
    private final TextMatcher startMatcher = new TextMatcher();
    private final TextMatcher endMatcher = new TextMatcher();
    /**
     * The target field for extracted text.
     * @param toField target field
     * @return target field
     */
    private String toField;
    private boolean inclusive;
    /**
     * The property setter to use when a value is set.
     * @param onSet property setter
     * @return property setter
     */
    private PropertySetter onSet;

    /**
     * Gets field matcher for fields on which to extract values.
     * @return field matcher
     */
    public TextMatcher getFieldMatcher() {
        return fieldMatcher;
    }

    /**
     * Sets field matcher for fields on which to extract values.
     * @param fieldMatcher field matcher
     */
    public TextBetweenOperation setFieldMatcher(TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }

    /**
     * Gets the start delimiter matcher for text to extract.
     * @return start delimiter matcher
     */
    public TextMatcher getStartMatcher() {
        return startMatcher;
    }

    /**
     * Sets the start delimiter matcher for text to extract.
     * @param startMatcher start delimiter matcher
     */
    public TextBetweenOperation setStartMatcher(TextMatcher startMatcher) {
        this.startMatcher.copyFrom(startMatcher);
        return this;
    }

    /**
     * Gets the end delimiter matcher for text to extract.
     * @return end delimiter matcher
     */
    public TextMatcher getEndMatcher() {
        return endMatcher;
    }

    /**
     * Sets the end delimiter matcher for text to extract.
     * @param endMatcher end delimiter matcher
     */
    public TextBetweenOperation setEndMatcher(TextMatcher endMatcher) {
        this.endMatcher.copyFrom(endMatcher);
        return this;
    }
}
