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

import com.norconex.commons.lang.text.TextMatcher;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>Analyzes the content of the supplied document and adds statistical
 * information about its content or field as metadata fields.  Default
 * behavior provide the statistics about the content. Refer to the following
 * for the new metadata fields to be created along with their description.</p>
 *
 * <table border="1">
 *  <caption>Statistic fields</caption>
 *   <tr>
 *     <th>Field name</th>
 *     <th>Description</th>
 *   </tr>
 *   <tr>
 *     <td>document.stat.characterCount</td>
 *     <td>Total number of characters (excluding carriage returns/line
 *         feed).</td>
 *   </tr>
 *   <tr>
 *     <td>document.stat.wordCount</td>
 *     <td>Total number of words.</td>
 *   </tr>
 *   <tr>
 *     <td>document.stat.sentenceCount</td>
 *     <td>Total number of sentences.</td>
 *   </tr>
 *   <tr>
 *     <td>document.stat.paragraphCount</td>
 *     <td>Total number of paragraph.</td>
 *   </tr>
 *   <tr>
 *     <td>document.stat.averageWordCharacterCount</td>
 *     <td>Average number of character in every words.</td>
 *   </tr>
 *   <tr>
 *     <td>document.stat.averageSentenceCharacterCount</td>
 *     <td>Average number of character in sentences (including non-word
 *         characters, such as spaces, or slashes).</td>
 *   </tr>
 *   <tr>
 *     <td>document.stat.averageSentenceWordCount</td>
 *     <td>Average number of words per sentences.</td>
 *   </tr>
 *   <tr>
 *     <td>document.stat.averageParagraphCharacterCount</td>
 *     <td>Average number of characters in paragraphs (including non-word
 *         characters, such as spaces, or slashes).</td>
 *   </tr>
 *   <tr>
 *     <td>document.stat.averageParagraphSentenceCount</td>
 *     <td>Average number of sentences per paragraphs.</td>
 *   </tr>
 *   <tr>
 *     <td>document.stat.averageParagraphWordCount</td>
 *     <td>Average number of words per paragraphs.</td>
 *   </tr>
 * </table>
 *
 * <p>You can specify a field matcher to obtain statistics about matching
 * fields instead.
 * When you do so, the field name will be inserted in the above
 * names, right after "document.stat.". E.g.:
 * <code>document.stat.myfield.characterCount</code></p>
 *
 * <p>Can be used both as a pre-parse (text-only) or post-parse handler.</p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.TextStatisticsTagger"
 *     {@nx.include com.norconex.importer.handler.tagger.AbstractCharStreamTagger#attributes}>
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <fieldMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *     (optional expression matching source fields to analyze instead of content)
 *   </fieldMatcher>
 *
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="TextStatisticsTagger">
 *   <fieldMatcher>statistics</fieldMatcher>
 * </handler>
 * }
 * <p>
 * The above create statistics from the value of a field called "statistics".
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class TextStatisticsTransformerConfig extends BaseDocHandlerConfig {

    private Charset sourceCharset;
    private final TextMatcher fieldMatcher = new TextMatcher();

    /**
     * Gets field matcher for fields to split.
     * @return field matcher
     */
    public TextMatcher getFieldMatcher() {
        return fieldMatcher;
    }

    /**
     * Sets the field matcher for fields to split.
     * @param fieldMatcher field matcher
     */
    public TextStatisticsTransformerConfig setFieldMatcher(
            TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }
}
