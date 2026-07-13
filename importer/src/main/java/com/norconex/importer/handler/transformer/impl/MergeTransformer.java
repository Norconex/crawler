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
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;

import lombok.Data;

/**
 * <p>
 * Merge multiple metadata fields into a single one.
 * </p>
 * <p>
 * Use <code>fromFields</code> to list all fields to merge, separated by commas.
 * Use <code>fromFieldsRegex</code> to match fields to merge using a regular
 * expression.
 * Both <code>fromFields</code> and <code>fromFieldsRegex</code> can be used
 * together. Matching fields from both will be combined, in the order
 * provided/matched, starting with <code>fromFields</code> entries.
 * </p>
 * <p>
 * Unless
 * <code>singleValue</code> is set to <code>true</code>, each value will be
 * added to the target field, making it a multi-value field.  If
 * <code>singleValue</code> is set to <code>true</code>,
 * all values will be combined into one string, optionally
 * separated by the <code>singleValueSeparator</code>.  Single values will
 * be constructed without any separator if none are specified.
 * </p>
 * <p>
 * You can optionally decide do delete source fields after they were merged
 * by setting <code>deleteFromFields</code> to <code>true</code>.
 * </p>
 * <p>
 * The target field can be one of the "from" fields. In such case its
 * content will be replaced with the result of the merge (it will not be
 * deleted even if <code>deleteFromFields</code> is <code>true</code>).
 * </p>
 * <p>
 * If only a single source field is specified or found, it will be copied
 * to the target field and its multi-values will still be merged to a single one
 * if configured to do so.  In such cases, this class can become an alternative
 * to using {@link ForceSingleValueTagger} with a "mergeWith" action.
 * </p>
 *
 * <p>Can be used both as a pre-parse or post-parse handler.</p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.MergeTagger">
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <!-- multiple merge tags allowed -->
 *   <merge toField="(name of target field for merged values)"
 *       deleteFromFields="[false|true]"
 *       singleValue="[false|true]"
 *       singleValueSeparator="(text joining multiple-values)">
 *     <fieldMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *       (one or more matching fields to merge)
 *     </fieldMatcher>
 *   </merge>
 *
 * </handler>
 * }
 * {@nx.xml.example
 * <handler class="MergeTagger">
 *   <merge toField="title" deleteFromFields="true"
 *       singleValue="true" singleValueSeparator="," >
 *     <fieldMatcher method="regex">(title|dc.title|dc:title|doctitle)</fieldMatcher>
 *   </merge>
 * </handler>
 * }
 *
 * <p>
 * The following merges several title fields into one, joining multiple
 * occurrences with a coma, and deleting original fields.
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
public class MergeTransformer
        implements ConfigurableDocHandler<MergeTransformerConfig> {

    private final MergeTransformerConfig configuration =
            new MergeTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {

        for (MergeOperation op : configuration.getOperations()) {

            // Merge values in one list
            List<String> mergedValues = new ArrayList<>();

            for (Entry<String, List<String>> en : docCtx.metadata()
                    .matchKeys(op.getFieldMatcher()).entrySet()) {

                var field = en.getKey();
                var values = en.getValue();

                // Merge values in one list
                mergedValues.addAll(values);

                // Delete if necessary
                if (op.isDeleteFromFields()) {
                    docCtx.metadata().remove(field);
                }
            }

            // Store in target field
            if (op.isSingleValue()) {
                docCtx.metadata().set(
                        op.getToField(), StringUtils.join(
                                mergedValues, op.getSingleValueSeparator()));
            } else {
                docCtx.metadata().put(op.getToField(), mergedValues);
            }
        }
        return true;
    }
}
