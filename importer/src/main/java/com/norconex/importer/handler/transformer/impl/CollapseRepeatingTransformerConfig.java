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

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import com.norconex.commons.lang.bean.jackson.JsonXmlCollection;
import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.commons.lang.io.TextReader;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.util.chunk.ChunkedTextSupport;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>Reduces specified consecutive characters or strings to only one
 * instance (document content only).
 * If reducing duplicate words, you usually have to add a space at the
 * Beginning or end of the word.
 * </p>
 * <p>
 * This class can be used as a pre-parsing (text content-types only)
 * or post-parsing handlers.
 * </p>
 * <p>
 * For more advanced replacement needs, consider using
 * {@link ReplaceTransformer} instead.
 * </p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.transformer.impl.ReduceConsecutivesTransformer"
 *     ignoreCase="[false|true]"
 *     {@nx.include com.norconex.importer.handler.transformer.AbstractStringTransformer#attributes}>
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <!-- multiple reduce tags allowed -->
 *   <reduce>(character or string to strip)</reduce>
 *
 * </handler>
 * }
 * <p>
 * In addition to regular characters, you can specify these special characters
 * in your XML:
 * </p>
 * <ul>
 *   <li>\r (carriage returns)</li>
 *   <li>\n (line feed)</li>
 *   <li>\t (tab)</li>
 *   <li>\s (space)</li>
 * </ul>
 * {@nx.xml.example
 * <handler class="ReduceConsecutivesTransformer">
 *   <reduce>\s</reduce>
 * </handler>
 * }
 * <p>
 * The above example reduces multiple spaces into a single one.
 * </p>
 *
 * @see ReplaceTransformer
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class CollapseRepeatingTransformerConfig extends BaseDocHandlerConfig
        implements ChunkedTextSupport {

    private int maxReadSize = TextReader.DEFAULT_MAX_READ_SIZE;
    private Charset sourceCharset;
    private final TextMatcher fieldMatcher = new TextMatcher();
    private boolean ignoreCase;
    @JsonXmlCollection
    private final List<String> strings = new ArrayList<>();

    public List<String> getStrings() {
        return new ArrayList<>(strings);
    }

    public CollapseRepeatingTransformerConfig setStrings(
            List<String> strings) {
        CollectionUtil.setAll(this.strings, strings);
        return this;
    }

    /**
     * Gets whether to ignore case sensitivity.
     * @return <code>true</code> if ignoring character case
     */
    public boolean isIgnoreCase() {
        return ignoreCase;
    }

    /**
     * Sets whether to ignore case sensitivity.
     * @param ignoreCase <code>true</code> if ignoring character case
     * @return this instance
     */
    public CollapseRepeatingTransformerConfig setIgnoreCase(
            boolean ignoreCase) {
        this.ignoreCase = ignoreCase;
        return this;
    }

    /**
     * Gets source field matcher for fields on which to perform repeating
     * string collapsing.
     * @return field matcher
     */
    @Override
    public TextMatcher getFieldMatcher() {
        return fieldMatcher;
    }

    /**
     * Sets source field matcher for fields on which to perform repeating
     * string collapsing.
     * @param fieldMatcher field matcher
     * @return this instance
     */
    public CollapseRepeatingTransformerConfig setFieldMatcher(
            TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }
}
