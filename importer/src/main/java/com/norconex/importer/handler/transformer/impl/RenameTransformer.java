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
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;

import lombok.Data;

/**
 * <p>Rename metadata fields to different names.
 * </p>
 * <h2>Storing values in an existing field</h2>
 * <p>
 * If a target field with the same name already exists for a document,
 * values will be added to the end of the existing value list.
 * It is possible to change this default behavior by supplying a
 * {@link PropertySetter}.
 * </p>
 * <p>
 * When using regular expressions, "toField" can also hold replacement patterns
 * (e.g. $1, $2, etc).
 * </p>
 *
 * <p>Can be used both as a pre-parse or post-parse handler.</p>
 *
 * @see <a href="https://crawler.norconex.com/docs/reference/importer/RenameTransformer">
 *      RenameTransformer configuration reference</a>
 */
@Data
public class RenameTransformer
        implements ConfigurableDocHandler<RenameTransformerConfig> {

    private final RenameTransformerConfig configuration =
            new RenameTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {
        for (RenameOperation op : configuration.getOperations()) {
            for (Entry<String, List<String>> en : docCtx.metadata().matchKeys(
                    op.getFieldMatcher()).entrySet()) {
                var field = en.getKey();
                var values = en.getValue();
                var newField = op.getFieldMatcher().replace(
                        field, op.getToField());
                if (!Objects.equals(field, newField)) {
                    docCtx.metadata().remove(field);
                    PropertySetter.orAppend(op.getOnSet()).apply(
                            docCtx.metadata(), newField, values);
                }
            }
        }
        return true;
    }
}
