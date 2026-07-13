/* Copyright 2021-2026 Norconex Inc.
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
package com.norconex.importer.handler.condition.impl;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.norconex.commons.lang.map.PropertyMatcher;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.handler.condition.ConfigurableCondition;
import com.norconex.importer.util.chunk.ChunkedTextReader;

import lombok.Data;

/**
 * <p>
 * A condition based on a text pattern matching a document content
 * (default), or matching specific field(s).
 * When used on very large content, it is possible the pattern matching will
 * be done in chunks, sometimes not achieving expected results.  Consider
 * creating your own condition from {@link AbstractCharStreamCondition}
 * if this is a concern.
 * </p>
 *
 * {@nx.xml.usage
 * <condition class="com.norconex.importer.handler.condition.impl.TextCondition"
 *     {@nx.include com.norconex.importer.handler.condition.AbstractStringCondition#attributes}>
 *
 *   <fieldMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *     (Optional expression of field to match. Omit to use document content.)
 *   </fieldMatcher>
 *   <valueMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *     (expression of value to match)
 *   </valueMatcher>
 *
 * </condition>
 * }
 *
 * {@nx.xml.example
 *  <condition class="TextCondition">
 *    <valueMatcher>apple</valueMatcher>
 *  </condition>
 * }
 *
 */
@SuppressWarnings("javadoc")
@Data
public class TextCondition
        implements ConfigurableCondition<TextConditionConfig> {

    private final TextConditionConfig configuration =
            new TextConditionConfig();

    public TextCondition() {
    }

    public TextCondition(TextMatcher valueMatcher) {
        configuration.setValueMatcher(valueMatcher);
    }

    public TextCondition(TextMatcher fieldMatcher, TextMatcher valueMatcher) {
        configuration
                .setValueMatcher(valueMatcher)
                .setFieldMatcher(fieldMatcher);
    }

    @Override
    public boolean test(DocHandlerContext docCtx) throws IOException {
        var matches = new AtomicBoolean();
        ChunkedTextReader.builder()
                .charset(configuration.getSourceCharset())
                .fieldMatcher(configuration.getFieldMatcher())
                .maxChunkSize(configuration.getMaxReadSize())
                .build()
                .read(docCtx, chunk -> {
                    if (!matches.get()
                            && textMatches(docCtx, chunk.getText())) {
                        matches.set(true);
                    }
                    return true;
                });
        return matches.get();
    }

    private boolean textMatches(DocHandlerContext docCtx, String input) {

        // content
        if (configuration.getFieldMatcher().getPattern() == null) {
            return configuration.getValueMatcher().matches(input);
        }

        // field(s)
        return new PropertyMatcher(
                configuration.getFieldMatcher(),
                configuration.getValueMatcher())
                        .matches(docCtx.metadata());
    }
}
