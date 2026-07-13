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

import java.util.regex.Pattern;

import com.norconex.commons.lang.text.TextMatcher;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>Keep only the metadata fields provided, delete all other ones.
 * Exact field names (case-insensitive)
 * to keep can be provided as well as a regular expression that matches
 * one or many fields (since 2.1.0).</p>
 *
 * <p><b>Note:</b> Unless you have good reasons for doing otherwise, it is
 * recommended to use this handler as one of the last ones to be executed.
 * This is a good practice to ensure all metadata fields are available
 * to other handlers that may require them even if they are not otherwise
 * required.</p>
 *
 * <p>Can be used both as a pre-parse or post-parse handler.</p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.KeepOnlyTagger">
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <fieldMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *     (one or more matching fields to keep)
 *   </fieldMatcher>
 *  </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="KeepOnlyTagger">
 *   <fieldMatcher method="regex">(title|description)</fieldMatcher>
 * </handler>
 * }
 * <p>
 * The above example keeps only the title and description fields from all
 * extracted fields.
 * </p>
 *
 * @see Pattern
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class KeepOnlyTransformerConfig extends BaseDocHandlerConfig {

    private final TextMatcher fieldMatcher = new TextMatcher();

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
    public void setFieldMatcher(TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
    }
}
