/* Copyright 2010-2026 Norconex Inc.
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
import java.util.List;
import java.util.Map.Entry;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;

import lombok.Data;

/**
 * <p>
 * Forces a metadata field to be single-value.  The action can be one of the
 * following:
 * </p>
 * <p>Can be used both as a pre-parse or post-parse handler.</p>
 * <pre>
 *    keepFirst          Keeps the first occurrence found.
 *    keepLast           Keeps the first occurrence found.
 *    mergeWith:&lt;sep&gt;    Merges all occurrences, joining them with the
 *                       specified separator (&lt;sep&gt;).
 * </pre>
 * <p>
 * If you do not specify any action, the default behavior is to merge all
 * occurrences, joining values with a comma.
 * </p>
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.ForceSingleValueTagger"
 *     action="[keepFirst|keepLast|mergeWith:separator]">
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <fieldMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *       (one or more matching fields to force having a single value)
 *   </fieldMatcher>
 *
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="ForceSingleValueTagger" action="keepFirst">
 *   <fieldMatcher>title</fieldMatcher>
 * </handler>
 * }
 * <p>
 * For documents where multiple title fields are found, the above only
 * keeps the first title value captured.
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
public class ForceSingleValueTransformer
        implements ConfigurableDocHandler<ForceSingleValueTransformerConfig> {

    private final ForceSingleValueTransformerConfig configuration =
            new ForceSingleValueTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {
        var action = configuration.getAction();
        for (Entry<String, List<String>> en : docCtx.metadata().matchKeys(
                configuration.getFieldMatcher()).entrySet()) {
            var field = en.getKey();
            var values = en.getValue();
            if (values != null && !values.isEmpty()
                    && StringUtils.isNotBlank(action)) {
                String singleValue = null;
                if ("keepFirst".equalsIgnoreCase(action)) {
                    singleValue = values.get(0);
                } else if ("keepLast".equalsIgnoreCase(action)) {
                    singleValue = values.get(values.size() - 1);
                } else if (Strings.CI.startsWith(
                        action, "mergeWith")) {
                    var sep = StringUtils.substringAfter(action, ":");
                    singleValue = StringUtils.join(values, sep);
                } else {
                    singleValue = StringUtils.join(values, ",");
                }
                docCtx.metadata().set(field, singleValue);
            }
        }
        return true;
    }
}
