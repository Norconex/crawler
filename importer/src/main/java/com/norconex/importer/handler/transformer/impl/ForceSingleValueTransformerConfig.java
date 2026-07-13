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

import com.norconex.commons.lang.text.TextMatcher;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * Forces a metadata field to be single-value.  The action can be one of the
 * following:
 * </p>
 * <p>Can be used both as a pre-parse or post-parse handler.</p>
 * <pre>
 *    keepFirst          Keeps the first occurrence found.
 *    keepLast           Keeps the first occurrence found.
 *    mergeWith:&lt;sep&gt;    Merges all occurrences, joining them with the
 *                       specified separator (&lt;sep&gt;).
 * </pre>
 * <p>
 * If you do not specify any action, the default behavior is to merge all
 * occurrences, joining values with a comma.
 * </p>
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.ForceSingleValueTagger"
 *     action="[keepFirst|keepLast|mergeWith:separator]">
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <fieldMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *       (one or more matching fields to force having a single value)
 *   </fieldMatcher>
 *
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="ForceSingleValueTagger" action="keepFirst">
 *   <fieldMatcher>title</fieldMatcher>
 * </handler>
 * }
 * <p>
 * For documents where multiple title fields are found, the above only
 * keeps the first title value captured.
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class ForceSingleValueTransformerConfig extends BaseDocHandlerConfig {

    private final TextMatcher fieldMatcher = new TextMatcher();

    /**
     * The action. One of: keepFirst, keepLast, or mergeWith:&lt;sep&gt;.
     * @param action action to be performed
     * @return action action to be performed
     */
    private String action;

    /**
     * Gets field matcher.
     * @return field matcher
     */
    public TextMatcher getFieldMatcher() {
        return fieldMatcher;
    }

    /**
     * Sets field matcher.
     * @param fieldMatcher field matcher
     */
    public ForceSingleValueTransformerConfig setFieldMatcher(
            TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }
}
