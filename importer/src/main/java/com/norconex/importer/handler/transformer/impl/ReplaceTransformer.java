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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.util.chunk.ChunkedTextReader;
import com.norconex.importer.util.chunk.TextChunk;

import lombok.Data;

/**
 * <p>Replaces every occurrences of the given replacements
 * (document content only).</p>
 *
 * <p>This class can be used as a pre-parsing (text content-types only)
 * or post-parsing handlers.</p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.transformer.impl.ReplaceTransformer"
 *     {@nx.include com.norconex.importer.handler.transformer.AbstractStringTransformer#attributes}>
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <!-- multiple replace tags allowed -->
 *   <replace>
 *     <valueMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#attributes}>
 *       (one or more source values to replace)
 *     </valueMatcher>
 *     <toValue>(replacement value)</toValue>
 *   </replace>
 *
 *  </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="ReplaceTransformer">
 *   <replace>
 *     <valueMatcher replaceAll="true">junk food</valueMatcher>
 *     <toValue>healthy food</toValue>
 *   </replace>
 * </handler>
 * }
 * <p>
 * The above example reduces all occurrences of "junk food" with "healthy food".
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
public class ReplaceTransformer
        implements ConfigurableDocHandler<ReplaceTransformerConfig> {

    private final ReplaceTransformerConfig configuration =
            new ReplaceTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {
        for (ReplaceOperation op : configuration.getOperations()) {
            ChunkedTextReader.builder()
                    .charset(configuration.getSourceCharset())
                    .fieldMatcher(op.getFieldMatcher())
                    .maxChunkSize(configuration.getMaxReadSize())
                    .build()
                    .read(docCtx, chunk -> {
                        doReplaceOnChunk(op, docCtx, chunk);
                        return true;
                    });
        }
        return true;
    }

    private void doReplaceOnChunk(
            ReplaceOperation op, DocHandlerContext docCtx, TextChunk chunk)
            throws IOException {

        // About fields:
        // Because replace can result in removing a value from a list, we
        // can't rely on the chunk field values index. So we perform the replace
        // on all values of a field here and we ignore the value index beyond
        // the first.
        if (chunk.getFieldValueIndex() > 0) {
            return;
        }

        List<String> sourceValues;
        if (chunk.getField() == null) {
            //body
            sourceValues = List.of(chunk.getText());
        } else {
            //field
            sourceValues = docCtx.metadata().getStrings(chunk.getField());
        }

        List<String> newValues = new ArrayList<>();
        var toValue = Optional.ofNullable(op.getToValue()).orElse("");
        for (String sourceValue : sourceValues) {
            var newValue = op.getValueMatcher().replace(sourceValue, toValue);
            if (newValue != null && (!op.isDiscardUnchanged()
                    || !Objects.equals(sourceValue, newValue))) {
                newValues.add(newValue);
            }
        }

        if (chunk.getField() == null) {
            //body
            try (var out = docCtx.output().asWriter(
                    configuration.getSourceCharset())) {
                if (newValues.isEmpty()) {
                    out.write("");
                } else {
                    out.write(newValues.get(0));
                }
            }
        } else //field
        if (StringUtils.isNotBlank(op.getToField())) {
            // set on target field
            PropertySetter.orAppend(op.getOnSet()).apply(
                    docCtx.metadata(), op.getToField(), newValues);
        } else {
            // overwrite source field
            PropertySetter.REPLACE.apply(
                    docCtx.metadata(), chunk.getField(), newValues);
        }
    }
}
