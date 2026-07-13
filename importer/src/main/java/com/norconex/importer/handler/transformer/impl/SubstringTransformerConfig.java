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

import java.nio.charset.Charset;

import com.norconex.commons.lang.text.TextMatcher;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>Keep a substring of the content matching a begin and end character
 * indexes.
 * Useful when you have to
 * truncate long content, or when you know precisely where is located
 * the text to extract in some files.
 * </p>
 * <p>
 * The "begin" value is inclusive, while the "end" value
 * is exclusive.  Both are optional.  When not specified (or a negative value),
 * the index
 * is assumed to be the beginning and end of the content, respectively.
 * </p>
 * <p>
 * This class can be used as a pre-parsing (text content-types only)
 * or post-parsing handlers.</p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.transformer.impl.SubstringTransformer"
 *     {@nx.include com.norconex.importer.handler.transformer.AbstractCharStreamTransformer#attributes}
 *     begin="(number)" end="(number)">
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="SubstringTransformer" end="10000"/>
 * }
 * <p>
 * The above example truncates long text to be 10,000 characters maximum.
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class SubstringTransformerConfig extends BaseDocHandlerConfig {

    private Charset sourceCharset;
    private final TextMatcher fieldMatcher = new TextMatcher();

    /**
     * The beginning index (inclusive).
     * A negative value is treated the same as zero.
     * @param beginIndex beginning index
     * @return beginning index
     */
    private long begin = 0;
    /**
     * The end index (exclusive).
     * A negative value is treated as the content end.
     * @param endIndex end index
     * @return the end index
     */
    private long end = -1;

    /**
     * Gets source field matcher for fields to transform.
     * @return field matcher
     */
    public TextMatcher getFieldMatcher() {
        return fieldMatcher;
    }

    /**
     * Sets source field matcher for fields to transform.
     * @param fieldMatcher field matcher
     */
    public SubstringTransformerConfig setFieldMatcher(
            TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }
}
