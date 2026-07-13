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
 * Delete the metadata fields provided. Exact field names (case-insensitive)
 * to delete can be provided as well as a regular expression that matches
 * one or many fields.
 * </p>
 * <p>Can be used both as a pre-parse or post-parse handler.</p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.DeleteTagger">
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *     <fieldMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *       (one or more matching fields to delete)
 *     </fieldMatcher>
 * </handler>
 * }
 *
 * {@nx.xml.example
 *  <handler class="DeleteTagger">
 *    <fieldMatcher method="regex">^[Xx]-.*</fieldMatcher>
 *  </handler>
 * }
 * <p>
 * The above deletes all metadata fields starting with "X-".
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class DeleteTransformerConfig extends BaseDocHandlerConfig {

    private final TextMatcher fieldMatcher = new TextMatcher();

    /**
     * Gets field matcher for fields to delete. If not set, delete the
     * document content (sets it to zero length).
     * @return field matcher
     */
    public TextMatcher getFieldMatcher() {
        return fieldMatcher;
    }

    /**
     * Sets the field matcher for fields to delete. If not set, delete the
     * document content (sets it to zero length).
     * @param fieldMatcher field matcher
     */
    public DeleteTransformerConfig setFieldMatcher(TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }
}
