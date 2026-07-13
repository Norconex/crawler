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

import java.io.IOException;
import java.io.Reader;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.commons.lang.io.TextReader;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;

import lombok.Data;

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
public class CountMatchesTransformer
        implements ConfigurableDocHandler<CountMatchesTransformerConfig> {

    private final CountMatchesTransformerConfig configuration =
            new CountMatchesTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {
        // "toField" and value must be present.
        if (StringUtils.isBlank(configuration.getToField())) {
            throw new IllegalArgumentException("'toField' cannot be blank.");
        }
        if (configuration.getCountMatcher().getPattern() == null) {
            throw new IllegalArgumentException(
                    "'countMatcher' pattern cannot be null.");
        }

        var count = 0;
        if (configuration.getFieldMatcher().getPattern() == null) {
            count = countContentMatches(
                    docCtx.input().asReader(
                            configuration.getSourceCharset()));
        } else {
            count = countFieldMatches(docCtx.metadata());
        }

        PropertySetter.orAppend(configuration.getOnSet()).apply(
                docCtx.metadata(), configuration.getToField(), count);
        return true;

    }

    private int countFieldMatches(Properties metadata) {
        var count = 0;
        for (String value : metadata.matchKeys(
                configuration.getFieldMatcher()).valueList()) {
            var m = configuration.getCountMatcher().toRegexMatcher(value);
            while (m.find()) {
                count++;
            }
        }
        return count;
    }

    private int countContentMatches(Reader reader) throws IOException {
        var count = 0;
        String text = null;
        try (var tr = new TextReader(reader, configuration.getMaxReadSize())) {
            while ((text = tr.readText()) != null) {
                var m = configuration.getCountMatcher().toRegexMatcher(text);
                while (m.find()) {
                    count++;
                }
            }
        }
        return count;
    }
}
