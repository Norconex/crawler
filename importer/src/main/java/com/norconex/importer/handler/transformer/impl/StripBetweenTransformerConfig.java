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

import com.norconex.importer.handler.BaseDocHandlerConfig;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.norconex.commons.lang.bean.jackson.JsonXmlCollection;
import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.commons.lang.io.TextReader;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.util.chunk.ChunkedTextSupport;

import lombok.Data;
import lombok.experimental.Accessors;

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
@Accessors(chain = true)
public class StripBetweenTransformerConfig extends BaseDocHandlerConfig
        implements ChunkedTextSupport {

    private int maxReadSize = TextReader.DEFAULT_MAX_READ_SIZE;
    private Charset sourceCharset;
    private final TextMatcher fieldMatcher = new TextMatcher();

    @JsonXmlCollection(entryName = "op")
    private final List<StripBetweenOperation> operations = new ArrayList<>();

    /**
     * Gets source field matcher for fields to transform.
     * @return field matcher
     */
    @Override
    public TextMatcher getFieldMatcher() {
        return fieldMatcher;
    }

    /**
     * Sets source field matcher for fields to transform.
     * @param fieldMatcher field matcher
     */
    public StripBetweenTransformerConfig setFieldMatcher(
            TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }

    public List<StripBetweenOperation> getOperations() {
        return Collections.unmodifiableList(operations);
    }

    public StripBetweenTransformerConfig setOperations(
            List<StripBetweenOperation> operations) {
        CollectionUtil.setAll(this.operations, operations);
        return this;
    }
}
