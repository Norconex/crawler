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

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>Replaces every occurrences of the given replacements
 * (document content only).</p>
 *
 * <p>This class can be used as a pre-parsing (text content-types only)
 * or post-parsing handlers.</p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.transformer.impl.ReplaceTransformer"
 *     {@nx.include com.norconex.importer.handler.transformer.AbstractStringTransformer#attributes}>
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <!-- multiple replace tags allowed -->
 *   <replace>
 *     <valueMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#attributes}>
 *       (one or more source values to replace)
 *     </valueMatcher>
 *     <toValue>(replacement value)</toValue>
 *   </replace>
 *
 *  </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="ReplaceTransformer">
 *   <replace>
 *     <valueMatcher replaceAll="true">junk food</valueMatcher>
 *     <toValue>healthy food</toValue>
 *   </replace>
 * </handler>
 * }
 * <p>
 * The above example reduces all occurrences of "junk food" with "healthy food".
 * </p>
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class ReplaceTransformerConfig extends BaseDocHandlerConfig {

    private int maxReadSize = TextReader.DEFAULT_MAX_READ_SIZE;
    private Charset sourceCharset;
    @JsonXmlCollection(entryName = "op")
    private final List<ReplaceOperation> operations = new ArrayList<>();

    public List<ReplaceOperation> getOperations() {
        return Collections.unmodifiableList(operations);
    }

    public ReplaceTransformerConfig setOperations(
            List<ReplaceOperation> operations) {
        CollectionUtil.setAll(this.operations, operations);
        return this;
    }
}
