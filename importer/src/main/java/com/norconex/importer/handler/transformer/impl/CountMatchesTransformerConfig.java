/* Copyright 2016-2024 Norconex Inc.
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
import java.util.regex.Pattern;

import com.norconex.commons.lang.io.TextReader;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.commons.lang.text.TextMatcher;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * Counts the number of matches of a given string (or string pattern) and
 * store the resulting value in a field in the specified "toField".
 * </p>
 * <p>
 * If no "fieldMatcher" expression is specified, the document content will be
 * used.  If the "fieldMatcher" matches more than one field, the sum of all
 * matches will be stored as a single value. More often than not,
 * you probably want to set your "countMatcher" to "partial".
 * </p>
 * <h2>Storing values in an existing field</h2>
 * <p>
 * If a target field with the same name already exists for a document,
 * the count value will be added to the end of the existing value list.
 * It is possible to change this default behavior
 * with {@link #setOnSet(PropertySetter)}.
 * </p>
 *
 * <p>Can be used as a pre-parse tagger on text document only when matching
 * strings on document content, or both as a pre-parse or post-parse handler
 * when the "fieldMatcher" is used.</p>
 *
 * {@nx.xml.usage
 *  <handler class="com.norconex.importer.handler.tagger.impl.CountMatchesTagger"
 *      toField="(target field)"
 *      maxReadSize="(max characters to read at once)"
 *      {@nx.include com.norconex.importer.handler.tagger.AbstractCharStreamTagger#attributes}>
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <fieldMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *     (optional expression for fields used to count matches)
 *   </fieldMatcher>
 *
 *   <countMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *     (expression used to count matches)
 *   </countMatcher>
 *
 *  </handler>
 * }
 *
 * {@nx.xml.example
 *  <handler class="CountMatchesTagger" toField="urlSegmentCount">
 *    <fieldMatcher>document.reference</fieldMatcher>
 *    <countMatcher method="regex">/[^/]+</countMatcher>
 *  </handler>
 * }
 * <p>
 * The above will count the number of segments in a URL.
 * </p>
 *
 * @see Pattern
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class CountMatchesTransformerConfig extends BaseDocHandlerConfig {

    private TextMatcher fieldMatcher = new TextMatcher();
    private TextMatcher countMatcher = new TextMatcher();
    private Charset sourceCharset;

    /**
     * The target field.
     * @param toField target field
     * @return target field
     */
    private String toField;

    /**
     * Gets the property setter to use when a value is set.
     * @param onSet property setter
     * @return property setter
     */
    private PropertySetter onSet;

    /**
     * The maximum number of characters to read from content for tagging
     * at once. Default is {@link TextReader#DEFAULT_MAX_READ_SIZE}.
     * @param maxReadSize maximum read size
     * @return maximum read size
     */
    private int maxReadSize = TextReader.DEFAULT_MAX_READ_SIZE;

    /**
     * Gets the field matcher.
     * @return field matcher
     */
    public TextMatcher getFieldMatcher() {
        return fieldMatcher;
    }

    /**
     * Sets the field matcher.
     * @param fieldMatcher field matcher
     */
    public CountMatchesTransformerConfig setFieldMatcher(
            TextMatcher fieldMatcher) {
        this.fieldMatcher = fieldMatcher;
        return this;
    }

    /**
     * Gets the count matcher.
     * @return count matcher
     */
    public TextMatcher getCountMatcher() {
        return countMatcher;
    }

    /**
     * Sets the count matcher.
     * @param countMatcher count matcher
     */
    public CountMatchesTransformerConfig setCountMatcher(
            TextMatcher countMatcher) {
        this.countMatcher = countMatcher;
        return this;
    }
}
