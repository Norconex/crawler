/* Copyright 2014-2024 Norconex Inc.
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.norconex.commons.lang.collection.CollectionUtil;

import lombok.Data;
import lombok.experimental.Accessors;

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
@Accessors(chain = true)
public class DebugTransformerConfig {

    private final List<String> logFields = new ArrayList<>();
    private boolean logContent;
    private String logLevel;

    /**
     * The prefix to print before the actual log message.
     * @param prefix log entry prefix
     * @return log entry prefix
     */
    private String prefix;

    public List<String> getLogFields() {
        return Collections.unmodifiableList(logFields);
    }

    public DebugTransformerConfig setLogFields(List<String> logFields) {
        CollectionUtil.setAll(this.logFields, logFields);
        return this;
    }
}
