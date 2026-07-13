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

import com.norconex.commons.lang.text.TextMatcher;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>Changes the character case of matching fields and values according to
 * one of the following methods:</p>
 * <ul>
 *   <li>upper: Changes all characters to upper case.</li>
 *   <li>lower: Changes all characters values to lower case.</li>
 *   <li>words: Converts the first letter of each words to upper case,
 *           and leaves the character case of other characters unchanged.</li>
 *   <li>wordsFully: Converts the first letter of each words to upper case,
 *           and the rest to lower case.</li>
 *   <li>sentences: Converts the first letter of each sentence to upper case,
 *           and leaves the character case of other characters unchanged.</li>
 *   <li>sentencesFully: Converts the first letter of each sentence to upper
 *           case, and converts other characters to lower case.</li>
 *   <li>string: Converts the first letter of a string to upper case, and
 *           leaves the character case of other characters unchanged.</li>
 *   <li>stringFully: Converts the first letter of a string to upper
 *           case, and converts other characters to lower case.</li>
 *   <li>swap: Converts all upper case characters to lower case, and all
 *           lower case to upper case.</li>
 * </ul>
 * <p>The change of character case can be applied to one of the
 * following (defaults to "value" when unspecified):</p>
 * <ul>
 *   <li>value: Applies to the field values.</li>
 *   <li>field: Applies to the field name.</li>
 *   <li>both: Applies to both the field name and its values.</li>
 * </ul>
 * <p>Field names are referenced in a case insensitive manner.</p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.CharacterCaseTagger"
 *     type="[upper|lower|words|wordsFully|sentences|sentencesFully|string|stringFully|swap]"
 *     applyTo="[value|field|both]">
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <fieldMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *     (expression to narrow by matching fields)
 *   </fieldMatcher>
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <!-- Converts title to lowercase -->
 * <handler class="CharacterCaseTagger" type="lower" applyTo="field">
 *   <fieldMatcher>title</fieldMatcher>
 * </handler>
 * <!-- Make first title character uppercase -->
 * <handler class="CharacterCaseTagger" type="string" applyTo="value">
 *   <fieldMatcher>title</fieldMatcher>
 * </handler>
 * }
 * <p>
 * The above examples first convert a title to lower case except for the
 * first character.
 * </p>
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class CharacterCaseTransformerConfig extends BaseDocHandlerConfig {

    public static final String CASE_WORDS = "words";
    public static final String CASE_WORDS_FULLY = "wordsFully";
    public static final String CASE_UPPER = "upper";
    public static final String CASE_LOWER = "lower";
    public static final String CASE_SWAP = "swap";
    public static final String CASE_SENTENCES = "sentences";
    public static final String CASE_SENTENCES_FULLY = "sentencesFully";
    public static final String CASE_STRING = "string";
    public static final String CASE_STRING_FULLY = "stringFully";

    public static final String APPLY_VALUE = "value";
    public static final String APPLY_FIELD = "field";
    public static final String APPLY_BOTH = "both";

    private final TextMatcher fieldMatcher = new TextMatcher();

    /**
     * The type of character case transformation.
     * @param caseType type of case transformation
     * @return type of case transformation
     */
    private String caseType;

    /**
     * Whether to apply the case transformation to fields, values,
     * or both. Does not apply when using this transformer on document content.
     * @param applyTo one of "field", "value", or "both"
     * @return one of "field", "value", or "both"
     */
    private String applyTo;

    /**
     * Optional matcher of fields to apply transformation to.
     * When not specified, transformation is on the document content.
     * @return field matcher
     */
    public TextMatcher getFieldMatcher() {
        return fieldMatcher;
    }

    /**
     * Optional matcher of fields to apply transformation to.
     * When not specified, transformation is on the document content.
     * @param fieldMatcher field matcher
     */
    public CharacterCaseTransformerConfig setFieldMatcher(
            TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }
}
