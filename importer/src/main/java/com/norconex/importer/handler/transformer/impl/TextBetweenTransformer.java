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

import static org.apache.commons.lang3.StringUtils.firstNonBlank;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.collections4.ListValuedMap;
import org.apache.commons.collections4.MultiMapUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.util.chunk.ChunkedTextReader;

import lombok.Data;

/**
 * <p>Extracts and add values found between a matching start and
 * end strings to a document metadata field.
 * The matching string end-points are defined in pairs and multiple ones
 * can be specified at once. The field specified for a pair of end-points
 * is considered a multi-value field.</p>
 * <p>
 * If "fieldMatcher" is specified, it will use content from matching fields and
 * storing all text extracted into the target field, multi-value.
 * Else, the document content is used.
 * </p>
 *
 * <h2>Storing values in an existing field</h2>
 * <p>
 * If a target field with the same name already exists for a document,
 * values will be added to the end of the existing value list.
 * It is possible to change this default behavior by supplying a
 * {@link PropertySetter}.
 * </p>
 * <p>
 * This class can be used as a pre-parsing handler on text documents only
 * or a post-parsing handler.
 * </p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.TextBetweenTagger"
 *     {@nx.include com.norconex.importer.handler.tagger.AbstractStringTagger#attributes}>
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <!-- multiple textBetween tags allowed -->
 *   <textBetween
 *       toField="(target field name)"
 *       inclusive="[false|true]"
 *       {@nx.include com.norconex.commons.lang.map.PropertySetter#attributes}>
 *     <fieldMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *       (optional expression matching fields to perform extraction on)
 *     </fieldMatcher>
 *     <startMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *       (expression matching "left" delimiter)
 *     </startMatcher>
 *     <endMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *       (expression matching "right" delimiter)
 *     </endMatcher>
 *   </textBetween>
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="TextBetweenTransformer">
 *   <textBetween toField="content">
 *     <startMatcher>OPEN</startMatcher>
 *     <endMatcher>CLOSE</endMatcher>
 *   </textBetween>
 * </handler>
 * }
 * <p>
 * The above example extract the content between "OPEN" and
 * "CLOSE" strings, excluding these strings, and store it in a "content"
 * field.
 * </p>
 */
@SuppressWarnings("javadoc")
@Data
public class TextBetweenTransformer
        implements ConfigurableDocHandler<TextBetweenTransformerConfig> {

    private final TextBetweenTransformerConfig configuration =
            new TextBetweenTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {

        for (TextBetweenOperation op : configuration.getOperations()) {
            ListValuedMap<String, String> opExtractions =
                    MultiMapUtils.newListValuedHashMap();
            ChunkedTextReader.builder()
                    .charset(configuration.getSourceCharset())
                    .fieldMatcher(op.getFieldMatcher())
                    .maxChunkSize(configuration.getMaxReadSize())
                    .build()
                    .read(docCtx, chunk -> {
                        opExtractions.putAll(
                                firstNonBlank(
                                        op.getToField(),
                                        chunk.getField()),
                                doExtractTextBetween(op, chunk.getText()));
                        return true;
                    });
            opExtractions.asMap().forEach((fld, vals) -> {
                PropertySetter.orAppend(
                        op.getOnSet()).apply(docCtx.metadata(), fld, vals);
            });
        }
        return true;
    }

    private List<String> doExtractTextBetween(
            TextBetweenOperation op, String text) {
        List<Pair<Integer, Integer>> matches = new ArrayList<>();
        var leftMatch = op.getStartMatcher().toRegexMatcher(text);
        while (leftMatch.find()) {
            var rightMatch = op.getEndMatcher().toRegexMatcher(text);
            if (!rightMatch.find(leftMatch.end())) {
                break;
            }
            if (op.isInclusive()) {
                matches.add(
                        new ImmutablePair<>(
                                leftMatch.start(), rightMatch.end()));
            } else {
                matches.add(
                        new ImmutablePair<>(
                                leftMatch.end(), rightMatch.start()));
            }
        }
        List<String> values = new ArrayList<>();
        for (var i = matches.size() - 1; i >= 0; i--) {
            var matchPair = matches.get(i);
            var value = text.substring(
                    matchPair.getLeft(), matchPair.getRight());
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }
}
