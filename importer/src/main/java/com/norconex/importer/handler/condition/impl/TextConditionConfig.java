/* Copyright 2021-2024 Norconex Inc.
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
package com.norconex.importer.handler.condition.impl;

import java.nio.charset.Charset;

import com.norconex.commons.lang.io.TextReader;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.handler.condition.BaseConditionConfig;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * A condition based on a text pattern matching a document content
 * (default), or matching specific field(s).
 * When used on very large content, it is possible the pattern matching will
 * be done in chunks, sometimes not achieving expected results.  Consider
 * creating your own condition from {@link AbstractCharStreamCondition}
 * if this is a concern.
 * </p>
 *
 * {@nx.xml.usage
 * <condition class="com.norconex.importer.handler.condition.impl.TextCondition"
 *     {@nx.include com.norconex.importer.handler.condition.AbstractStringCondition#attributes}>
 *
 *   <fieldMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *     (Optional expression of field to match. Omit to use document content.)
 *   </fieldMatcher>
 *   <valueMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *     (expression of value to match)
 *   </valueMatcher>
 *
 * </condition>
 * }
 *
 * {@nx.xml.example
 *  <condition class="TextCondition">
 *    <valueMatcher>apple</valueMatcher>
 *  </condition>
 * }
 *
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class TextConditionConfig extends BaseConditionConfig {

    private final TextMatcher fieldMatcher = new TextMatcher();
    private final TextMatcher valueMatcher = new TextMatcher();

    /**
     * The presumed source character encoding. Usually ignored and presumed
     * to be UTF-8 if the document has been parsed already.
     * @param sourceCharset character encoding of the source to be transformed
     * @return character encoding of the source to be transformed
     */
    private Charset sourceCharset;

    /**
     * The maximum number of characters to read at once, used for filtering.
     * Default is {@link TextReader#DEFAULT_MAX_READ_SIZE}.
     * @param maxReadSize maximum read size
     * @return maximum read size
     */
    private int maxReadSize = TextReader.DEFAULT_MAX_READ_SIZE;

    /**
     * Gets the text matcher for content or field values.
     * @return text matcher
     */
    public TextMatcher getValueMatcher() {
        return valueMatcher;
    }

    /**
     * Sets the text matcher for content or field values. Copies it.
     * @param valueMatcher text matcher
     * @return this instance
     */
    public TextConditionConfig setValueMatcher(TextMatcher valueMatcher) {
        this.valueMatcher.copyFrom(valueMatcher);
        return this;
    }

    /**
     * Gets the text matcher of field names.
     * @return field matcher
     */
    public TextMatcher getFieldMatcher() {
        return fieldMatcher;
    }

    /**
     * Sets the text matcher of field names. Copies it.
     * @param fieldMatcher text matcher
     * @return this instance
     */
    public TextConditionConfig setFieldMatcher(TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }
}
