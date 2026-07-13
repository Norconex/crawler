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

import com.norconex.importer.handler.BaseDocHandlerConfig;

import java.nio.charset.Charset;

import com.norconex.commons.lang.io.TextReader;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.util.chunk.ChunkedTextSupport;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>Strips any content found before first match found for given pattern.</p>
 *
 * <p>This class can be used as a pre-parsing (text content-types only)
 * or post-parsing handlers.</p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.transformer.impl.StripBeforeTransformer"
 *     inclusive="[false|true]"
 *     {@nx.include com.norconex.importer.handler.transformer.AbstractStringTransformer#attributes}>
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <stripBeforeMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>>
 *     (expression matching text up to which to strip)
 *   </stripBeforeMatcher>
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="StripBeforeTransformer" inclusive="true">
 *   <stripBeforeMatcher><![CDATA[<!-- HEADER_END -->]]></stripBeforeMatcher>
 * </handler>
 * }
 *
 * <p>
 * The above example will strip all text up to and including this HTML comment:
 * <code>&lt;!-- HEADER_END --&gt;</code>.
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class StripBeforeTransformerConfig extends BaseDocHandlerConfig
        implements ChunkedTextSupport {

    private int maxReadSize = TextReader.DEFAULT_MAX_READ_SIZE;
    private Charset sourceCharset;
    private final TextMatcher fieldMatcher = new TextMatcher();

    /**
     * Whether the match itself should be stripped or not.
     * @param inclusive <code>true</code> to strip the matched characters
     * @return <code>true</code> if stripping the matched characters
     */
    private boolean inclusive;
    private final TextMatcher stripBeforeMatcher = new TextMatcher();

    /**
     * Gets source field matcher for fields to transform.
     * @return field matcher
     */
    @Override
    public TextMatcher getFieldMatcher() {
        return fieldMatcher;
    }

    /**
     * Sets source field matcher for fields to transform.
     * @param fieldMatcher field matcher
     */
    public StripBeforeTransformerConfig setFieldMatcher(
            TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }

    /**
     * Gets the matcher for the text up to which to strip content.
     * @return text matcher
     */
    public TextMatcher getStripBeforeMatcher() {
        return stripBeforeMatcher;
    }

    /**
     * Sets the matcher for the text up to which to strip content.
     * @param stripBeforeMatcher text matcher
     */
    public StripBeforeTransformerConfig setStripBeforeMatcher(
            TextMatcher stripBeforeMatcher) {
        this.stripBeforeMatcher.copyFrom(stripBeforeMatcher);
        return this;
    }
}
