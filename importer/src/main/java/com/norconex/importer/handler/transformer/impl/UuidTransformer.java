/* Copyright 2017-2024 Norconex Inc.
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
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;

import lombok.Data;

/**
 * <p>Generates a random Universally unique identifier (UUID) and stores it
 * in the specified <code>field</code>.
 * If no <code>field</code> is provided, the UUID will be added to
 * <code>document.uuid</code>.
 * </p>
 *
 * <h2>Storing values in an existing field</h2>
 * <p>
 * If a target field with the same name already exists for a document,
 * values will be added to the end of the existing value list.
 * It is possible to change this default behavior by supplying a
 * {@link PropertySetter}.
 * </p>
 *
 * <p>Can be used both as a pre-parse or post-parse handler.</p>
 *
 * @see <a href="https://crawler.norconex.com/docs/reference/importer/UuidTransformer">
 *      UuidTransformer configuration reference</a>
 */
@Data
public class UuidTransformer
        implements ConfigurableDocHandler<UuidTransformerConfig> {

    private final UuidTransformerConfig configuration =
            new UuidTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {

        var uuid = UUID.randomUUID().toString();
        var finalField = configuration.getToField();
        if (StringUtils.isBlank(finalField)) {
            finalField = UuidTransformerConfig.DEFAULT_FIELD;
        }
        PropertySetter.orAppend(configuration.getOnSet()).apply(
                docCtx.metadata(), finalField, uuid);
        return true;
    }
}
