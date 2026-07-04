/* Copyright 2014-2026 Norconex Inc.
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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.lang3.StringUtils.trimToEmpty;

import java.io.IOException;
import java.util.List;
import java.util.Map.Entry;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.event.Level;

import com.norconex.commons.lang.Slf4jUtil;
import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>A utility tagger to help with troubleshooting of document importing.
 * Place this tagger anywhere in your handler configuration to print to
 * the log stream the metadata fields or content so far when this handler
 * gets invoked.
 * This handler does not impact the data being imported at all
 * (it only reads it).</p>
 *
 * <p>The default behavior logs all metadata fields using the DEBUG log level.
 * You can optionally set which fields to log and whether to also log the
 * document content or not, as well as specifying a different log level.</p>
 *
 * <p><b>Be careful:</b> Logging the content when you deal with very large
 * content can result in memory exceptions.</p>
 *
 * <p>Can be used both as a pre-parse or post-parse handler.</p>
 *
 * {@nx.xml.usage
 *  <handler class="com.norconex.importer.handler.tagger.impl.DebugTagger"
 *          logFields="(CSV list of fields to log)"
 *          logContent="[false|true]"
 *          logLevel="[ERROR|WARN|INFO|DEBUG|TRACE]"
 *          prefix="(optional log prefix to further help you locate it)" >
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *  </handler>
 * }
 * {@nx.xml.example
 *  <handler class="DebugTagger" logFields="title,author" logLevel="INFO" />
 * }
 * <p>
 * The above logs the value of any "title" and "author" document metadata
 * fields.
 * </p>
 */
@SuppressWarnings("javadoc")

@Data
@Slf4j
public class DebugTransformer
        implements ConfigurableDocHandler<DebugTransformerConfig> {

    private final DebugTransformerConfig configuration =
            new DebugTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {

        var level = Level.valueOf(ObjectUtils.getIfNull(
                configuration.getLogLevel(), "debug").toUpperCase());

        if (configuration.getLogFields().isEmpty()) {
            for (Entry<String, List<String>> entry : docCtx.metadata()
                    .entrySet()) {
                logField(level, entry.getKey(), entry.getValue());
            }
        } else {
            for (String fieldName : configuration.getLogFields()) {
                logField(level, fieldName, docCtx.metadata().get(fieldName));
            }
        }

        if (configuration.isLogContent()) {
            Slf4jUtil.log(
                    LOG, level,
                    StringUtils.trimToEmpty(configuration.getPrefix())
                            + "CONTENT={}",
                    IOUtils.toString(docCtx.input().asInputStream(), UTF_8));
        }
        return true;

    }

    private void logField(Level level, String fieldName, List<String> values) {
        var b = new StringBuilder();
        if (values == null) {
            b.append("<null>");
        } else {
            for (String value : values) {
                if (!b.isEmpty()) {
                    b.append(", ");
                }
                b.append(value);
            }
        }
        Slf4jUtil.log(
                LOG, level, trimToEmpty(configuration.getPrefix())
                        + "{}={}",
                fieldName, b.toString());
    }
}
