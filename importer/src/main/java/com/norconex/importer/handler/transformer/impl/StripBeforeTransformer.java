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
 * <p>Strips any content found before first match found for given pattern.</p>
 *
 * <p>This class can be used as a pre-parsing (text content-types only)
 * or post-parsing handlers.</p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.transformer.impl.StripBeforeTransformer"
 *     inclusive="[false|true]"
 *     {@nx.include com.norconex.importer.handler.transformer.AbstractStringTransformer#attributes}>
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <stripBeforeMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>>
 *     (expression matching text up to which to strip)
 *   </stripBeforeMatcher>
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="StripBeforeTransformer" inclusive="true">
 *   <stripBeforeMatcher><![CDATA[<!-- HEADER_END -->]]></stripBeforeMatcher>
 * </handler>
 * }
 *
 * <p>
 * The above example will strip all text up to and including this HTML comment:
 * <code>&lt;!-- HEADER_END --&gt;</code>.
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
@Slf4j
public class StripBeforeTransformer
        implements ConfigurableDocHandler<StripBeforeTransformerConfig> {

    private final StripBeforeTransformerConfig configuration =
            new StripBeforeTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {
        if (!configuration.getStripBeforeMatcher().isSet()) {
            LOG.error("No matcher pattern provided.");
            return true;
        }

        ChunkedTextUtil.transform(configuration, docCtx, chunk -> {
            var b = new StringBuilder(chunk.getText());
            var m = configuration.getStripBeforeMatcher().toRegexMatcher(b);
            if (m.find()) {
                if (configuration.isInclusive()) {
                    b.delete(0, m.end());
                } else {
                    b.delete(0, m.start());
                }
            }
            return b.toString();
        });
        return true;
    }
}
