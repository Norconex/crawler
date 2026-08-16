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

import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Delete the metadata fields provided. Exact field names (case-insensitive)
 * to delete can be provided as well as a regular expression that matches
 * one or many fields.
 * </p>
 * <p>Can be used both as a pre-parse or post-parse handler.</p>
 *
 * @see <a href="https://crawler.norconex.com/docs/reference/importer/DeleteTransformer">
 *      DeleteTransformer configuration reference</a>
 */
@Data
@Slf4j
public class DeleteTransformer
        implements ConfigurableDocHandler<DeleteTransformerConfig> {

    private final DeleteTransformerConfig configuration =
            new DeleteTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {

        if (configuration.getFieldMatcher().isSet()) {
            // Fields
            for (String field : docCtx.metadata().matchKeys(
                    configuration.getFieldMatcher()).keySet()) {
                docCtx.metadata().remove(field);
                LOG.debug("Deleted field: {}", field);
            }
        } else {
            // Body
            try (var out = docCtx.output().asOutputStream()) {
                out.write(new byte[] {});
            }
        }
        return true;
    }
}
