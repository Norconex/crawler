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

/**
 * <p>Strips any content found between a matching start and end strings.  The
 * matching strings are defined in pairs and multiple ones can be specified
 * at once.</p>
 *
 * <p>This class can be used as a pre-parsing (text content-types only)
 * or post-parsing handlers.</p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.transformer.impl.StripBetweenTransformer"
 *     {@nx.include com.norconex.importer.handler.transformer.AbstractStringTransformer#attributes}>
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <!-- multiple stripBetween tags allowed -->
 *   <stripBetween
 *       inclusive="[false|true]">
 *     <startMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *       (expression matching "left" delimiter)
 *     </startMatcher>
 *     <endMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *       (expression matching "right" delimiter)
 *     </endMatcher>
 *   </stripBetween>
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="StripBetweenTransformer">
 *   <stripBetween inclusive="true">
 *     <startMatcher><![CDATA[<!-- SIDENAV_START -->]]></startMatcher>
 *     <endMatcher><![CDATA[<!-- SIDENAV_END -->]]></endMatcher>
 *   </stripBetween>
 * </handler>
 * }
 * <p>
 * The following will strip all text between (and including) these two
 * HTML comments:
 * <code>&lt;!-- SIDENAV_START --&gt;</code> and
 * <code>&lt;!-- SIDENAV_END --&gt;</code>.
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
public class StripBetweenTransformer
        implements ConfigurableDocHandler<StripBetweenTransformerConfig> {

    private final StripBetweenTransformerConfig configuration =
            new StripBetweenTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {
        ChunkedTextUtil.transform(configuration, docCtx, chunk -> {
            var b = new StringBuilder(chunk.getText());
            for (StripBetweenOperation op : configuration.getOperations()) {
                var leftMatch = op.getStartMatcher().toRegexMatcher(b);
                while (leftMatch.find()) {
                    var rightMatch = op.getEndMatcher().toRegexMatcher(b);
                    if (!rightMatch.find(leftMatch.end())) {
                        break;
                    }
                    if (op.isInclusive()) {
                        b.delete(leftMatch.start(), rightMatch.end());
                    } else {
                        b.delete(leftMatch.end(), rightMatch.start());
                    }
                    leftMatch = op.getStartMatcher().toRegexMatcher(b);
                }
            }
            return b.toString();
        });
        return true;
    }
}
