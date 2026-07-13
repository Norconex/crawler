/* Copyright 2014-2026 Norconex Inc.
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

import static com.norconex.importer.handler.transformer.impl.CharacterCaseTransformerConfig.APPLY_BOTH;
import static com.norconex.importer.handler.transformer.impl.CharacterCaseTransformerConfig.APPLY_FIELD;
import static com.norconex.importer.handler.transformer.impl.CharacterCaseTransformerConfig.APPLY_VALUE;
import static com.norconex.importer.handler.transformer.impl.CharacterCaseTransformerConfig.CASE_LOWER;
import static com.norconex.importer.handler.transformer.impl.CharacterCaseTransformerConfig.CASE_SENTENCES;
import static com.norconex.importer.handler.transformer.impl.CharacterCaseTransformerConfig.CASE_SENTENCES_FULLY;
import static com.norconex.importer.handler.transformer.impl.CharacterCaseTransformerConfig.CASE_STRING;
import static com.norconex.importer.handler.transformer.impl.CharacterCaseTransformerConfig.CASE_STRING_FULLY;
import static com.norconex.importer.handler.transformer.impl.CharacterCaseTransformerConfig.CASE_SWAP;
import static com.norconex.importer.handler.transformer.impl.CharacterCaseTransformerConfig.CASE_UPPER;
import static com.norconex.importer.handler.transformer.impl.CharacterCaseTransformerConfig.CASE_WORDS;
import static com.norconex.importer.handler.transformer.impl.CharacterCaseTransformerConfig.CASE_WORDS_FULLY;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.text.WordUtils;

import com.norconex.commons.lang.EqualsUtil;
import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.commons.lang.map.Properties;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>Changes the character case of a document content or matching fields
 * values according to one of the following methods:</p>
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
 * <p>When dealing with fields, the change of character case can be applied to
 * one of the following (defaults to "value" when unspecified):</p>
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
@Slf4j
@Data
public class CharacterCaseTransformer
        implements ConfigurableDocHandler<CharacterCaseTransformerConfig> {

    private final CharacterCaseTransformerConfig configuration =
            new CharacterCaseTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {
        if (configuration.getFieldMatcher().isSet()) {
            doFields(docCtx);
        } else {
            doBody(docCtx);
        }
        return true;
    }

    private void doFields(DocHandlerContext docCtx) {
        var applyTo = configuration.getApplyTo();
        if (StringUtils.isNotBlank(applyTo)
                && !Strings.CI.equalsAny(
                        applyTo, APPLY_FIELD, APPLY_VALUE, APPLY_BOTH)) {
            LOG.warn("Unsupported \"applyTo\": {}", applyTo);
            return;
        }

        for (Entry<String, List<String>> en : docCtx.metadata().matchKeys(
                configuration.getFieldMatcher()).entrySet()) {

            var field = en.getKey();
            var newField = field;

            // Do field
            if (EqualsUtil.equalsAny(applyTo, APPLY_FIELD, APPLY_BOTH)) {
                newField = changeFieldCase(field, docCtx.metadata());
            }

            // Do values
            if (StringUtils.isBlank(applyTo) || EqualsUtil.equalsAny(
                    applyTo, APPLY_VALUE, APPLY_BOTH)) {
                changeValuesCase(newField, docCtx.metadata());
            }
        }
    }

    private void doBody(DocHandlerContext docCtx) throws IOException {
        try (var out = docCtx.output().asWriter()) {
            docCtx.input().asChunkedText((idx, text) -> {
                out.write(changeCase(text));
                return true;
            });
        }
    }

    private String changeFieldCase(String field, Properties metadata) {
        var values = metadata.getStrings(field);
        var newField = changeCase(field);
        metadata.remove(field);
        if (values != null && !values.isEmpty()) {
            metadata.setList(newField, values);
        }
        return newField;
    }

    private void changeValuesCase(
            String field, Properties metadata) {
        var values = metadata.getStrings(field);
        if (values != null) {
            for (var i = 0; i < values.size(); i++) {
                values.set(i, changeCase(values.get(i)));
            }
            metadata.setList(field, values);
        }
    }

    private String changeCase(String value) {
        var type = configuration.getCaseType();

        if (CASE_UPPER.equals(type)) {
            return StringUtils.upperCase(value);
        }
        if (CASE_LOWER.equals(type)) {
            return StringUtils.lowerCase(value);
        }
        if (CASE_WORDS.equals(type)) {
            return WordUtils.capitalize(value);
        }
        if (CASE_WORDS_FULLY.equals(type)) {
            return WordUtils.capitalizeFully(value);
        }
        if (CASE_SWAP.equals(type)) {
            return WordUtils.swapCase(value);
        }
        if (CASE_STRING.equals(type)) {
            return capitalizeString(value);
        }
        if (CASE_STRING_FULLY.equals(type)) {
            return capitalizeStringFully(value);
        }
        if (CASE_SENTENCES.equals(type)) {
            return capitalizeSentences(value);
        }
        if (CASE_SENTENCES_FULLY.equals(type)) {
            return capitalizeSentencesFully(value);
        }
        LOG.warn("Unsupported character case type: {}", type);
        return value;
    }

    private String capitalizeString(String value) {
        if (StringUtils.isNotBlank(value)) {
            var m = Pattern.compile(
                    "^(.*?)([\\p{IsAlphabetic}\\p{IsDigit}])").matcher(value);
            if (m.find()) {
                var firstChar =
                        StringUtils.upperCase(m.group(2), Locale.ENGLISH);
                return m.replaceFirst("$1" + firstChar);
            }
        }
        return value;
    }

    private String capitalizeStringFully(String value) {
        return capitalizeString(StringUtils.lowerCase(value));
    }

    private String capitalizeSentences(String value) {
        if (StringUtils.isBlank(value)) {
            return value;
        }
        var pos = 0;
        var followedBySpace = true;
        var sentenceEnded = true;
        var b = new StringBuilder(value);
        while (pos < b.length()) {
            var ch = b.charAt(pos);
            if (ch == '.' || ch == '!' || ch == '?') {
                sentenceEnded = true;
            } else if (sentenceEnded && Character.isWhitespace(ch)) {
                followedBySpace = true;
            } else {
                if (sentenceEnded && followedBySpace) {
                    b.setCharAt(pos, Character.toUpperCase(b.charAt(pos)));
                }
                sentenceEnded = false;
                followedBySpace = false;
            }
            pos++;
        }
        return b.toString();
    }

    private String capitalizeSentencesFully(String value) {
        return capitalizeSentences(StringUtils.lowerCase(value));
    }
}
