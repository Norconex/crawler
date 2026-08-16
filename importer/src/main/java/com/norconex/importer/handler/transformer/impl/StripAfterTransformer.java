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
import com.norconex.importer.util.chunk.ChunkedTextUtil;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>Strips any content found after first match found for given pattern.</p>
 *
 * <p>This class can be used as a pre-parsing (text content-types only)
 * or post-parsing handlers.</p>
 *
 * @see <a href="https://crawler.norconex.com/docs/reference/importer/StripAfterTransformer">
 *      StripAfterTransformer configuration reference</a>
 */
@Data
@Slf4j
public class StripAfterTransformer
        implements ConfigurableDocHandler<StripAfterTransformerConfig> {

    private final StripAfterTransformerConfig configuration =
            new StripAfterTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {
        if (!configuration.getStripAfterMatcher().isSet()) {
            LOG.error("No matcher pattern provided.");
            return true;
        }

        ChunkedTextUtil.transform(configuration, docCtx, chunk -> {
            var b = new StringBuilder(chunk.getText());
            var m = configuration.getStripAfterMatcher().toRegexMatcher(b);
            if (m.find()) {
                if (configuration.isInclusive()) {
                    b.delete(m.start(), b.length());
                } else {
                    b.delete(m.end(), b.length());
                }
            }
            return b.toString();
        });
        return true;
    }
}
