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
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;

import lombok.Data;

/**
 * <p>Splits an existing metadata value into multiple values based on a given
 * value separator (the separator gets discarded).  The "toField" argument
 * is optional (the same field will be used to store the splits if no
 * "toField" is specified"). Duplicates are removed.</p>
 * <p>Can be used both as a pre-parse (metadata or text content) or
 * post-parse handler.</p>
 * <p>
 * If no "fieldMatcher" expression is specified, the document content will be
 * used.  If the "fieldMatcher" matches more than one field, they will all
 * be split and stored in the same multi-value metadata field.
 * </p>
 * <h2>Storing values in an existing field</h2>
 * <p>
 * If a target field with the same name already exists for a document,
 * values will be added to the end of the existing value list.
 * It is possible to change this default behavior by supplying a
 * {@link PropertySetter}.
 * </p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.SplitTagger"
 *     {@nx.include com.norconex.importer.handler.tagger.AbstractCharStreamTagger#attributes}>
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <!-- multiple split tags allowed -->
 *   <split
 *       toField="targetFieldName"
 *       {@nx.include com.norconex.commons.lang.map.PropertySetter#attributes}>
 *     <fieldMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *       (one or more matching fields to split)
 *     </fieldMatcher>
 *     <separator regex="[false|true]">(separator value)</separator>
 *   </split>
 *
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="SplitTagger">
 *   <split>
 *     <fieldMatcher>myField</fieldMatcher>
 *     <separator regex="true">\s*,\s*</separator>
 *   </split>
 * </handler>
 * }
 * <p>
 * The above example splits a single value field holding a comma-separated
 * list into multiple values.
 * </p>
 */
@SuppressWarnings("javadoc")
@Data
public class SplitTransformer
        implements ConfigurableDocHandler<SplitTransformerConfig> {

    private final SplitTransformerConfig configuration =
            new SplitTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {
        for (SplitOperation op : configuration.getOperations()) {
            if (op.getFieldMatcher().isSet()) {
                splitMetadata(op, docCtx);
            } else {
                splitContent(op, docCtx);
            }
        }
        return true;
    }

    private void splitContent(SplitOperation op, DocHandlerContext docCtx)
            throws IOException {

        var delim = op.getSeparator();
        if (!op.isSeparatorRegex()) {
            delim = Pattern.quote(delim);
        }
        List<String> targetValues = new ArrayList<>();
        try (var input = docCtx.input().asInputStream();
                var scanner = new Scanner(input).useDelimiter(delim);) {
            while (scanner.hasNext()) {
                targetValues.add(scanner.next());
            }
        }
        PropertySetter.orAppend(op.getOnSet()).apply(
                docCtx.metadata(), op.getToField(), targetValues);
    }

    private void splitMetadata(SplitOperation split, DocHandlerContext docCtx) {

        List<String> allTargetValues = new ArrayList<>();
        for (Entry<String, List<String>> en : docCtx.metadata().matchKeys(
                split.getFieldMatcher()).entrySet()) {
            var fromField = en.getKey();
            var sourceValues = en.getValue();
            List<String> targetValues = new ArrayList<>();
            for (String sourceValue : sourceValues) {
                if (split.isSeparatorRegex()) {
                    targetValues.addAll(
                            regexSplit(
                                    sourceValue, split.getSeparator()));
                } else {
                    targetValues.addAll(
                            regularSplit(
                                    sourceValue, split.getSeparator()));
                }
            }

            // toField is blank, we overwrite the source and do not
            // carry values further.
            if (StringUtils.isBlank(split.getToField())) {
                // overwrite source field
                PropertySetter.REPLACE.apply(
                        docCtx.metadata(), fromField, targetValues);
            } else {
                allTargetValues.addAll(targetValues);
            }
        }
        if (StringUtils.isNotBlank(split.getToField())) {
            // set on target field
            PropertySetter.orAppend(split.getOnSet()).apply(
                    docCtx.metadata(), split.getToField(), allTargetValues);
        }
    }

    private List<String> regexSplit(String metaValue, String separator) {
        var values = metaValue.split(separator);
        List<String> cleanValues = new ArrayList<>();
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                cleanValues.add(value);
            }
        }
        return cleanValues;
    }

    private List<String> regularSplit(String metaValue, String separator) {
        return Arrays.asList(
                StringUtils.splitByWholeSeparator(metaValue, separator));
    }
}
