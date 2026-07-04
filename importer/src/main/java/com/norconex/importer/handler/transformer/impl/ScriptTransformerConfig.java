/* Copyright 2015-2024 Norconex Inc.
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

import com.norconex.commons.lang.io.TextReader;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.handler.ScriptRunner;
import com.norconex.importer.util.chunk.ChunkedTextSupport;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * Transform incoming documents using a scripting language.
 * </p><p>
 * Refer to {@link ScriptRunner} for more information on using a scripting
 * language with Norconex Importer.
 * </p>
 * <h2>How to transform documents with scripting:</h2>
 * <p>
 * The following are variables made available to your script for each
 * document:
 * </p>
 * <ul>
 *   <li><b>reference:</b> Document unique reference as a string.</li>
 *   <li><b>content:</b> Document content, as a string
 *       (of <code>maxReadSize</code> length).</li>
 *   <li><b>metadata:</b> Document metadata as an {@link Properties}
 *       object.</li>
 *   <li><b>parsed:</b> Whether the document was already parsed, as a
 *       boolean.</li>
 *   <li><b>sectionIndex:</b> Content section index if it had to be split
 *       (as per <code>maxReadSize</code>), as an integer.</li>
 * </ul>
 * <p>
 * The expected <b>return value</b> from your script is a string holding
 * the modified content.
 * </p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.transformer.impl.ScriptTransformer"
 *     engineName="(script engine name)"
 *     {@nx.include com.norconex.importer.handler.transformer.AbstractStringTransformer#attributes}>
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <script>(your script)</script>
 *
 * </handler>
 * }
 *
 * <h4>Usage examples:</h4>
 * <p>
 * The following examples replace all occurrences of "Alice" with "Roger"
 * in a document content.
 * </p>
 * <h5>JavaScript:</h5>
 * {@nx.xml
 * <handler class="ScriptTransformer" engineName="JavaScript">
 *   <script><![CDATA[
 *       returnValue = content.replace(/Alice/g, 'Roger');
 *   ]]></script>
 * </handler>
 * }
 *
 * <h5>Lua:</h5>
 * {@nx.xml
 * <handler class="ScriptTransformer" engineName="lua">
 *   <script><![CDATA[
 *       returnValue = content:gsub('Alice', 'Roger');
 *   ]]></script>
 * </handler>
 * }
 *
 * <h5>Python:</h5>
 * {@nx.xml
 * <handler class="ScriptTransformer" engineName="python">
 *   <script><![CDATA[
 *       returnValue = content.replace('Alice', 'Roger')
 *   ]]></script>
 * </handler>
 * }
 *
 * <h5>Velocity:</h5>
 * {@nx.xml
 * <handler class="ScriptTransformer" engineName="velocity">
 *   <script><![CDATA[
 *       #set($returnValue = $content.replace('Alice', 'Roger'))
 *   ]]></script>
 * </handler>
 * }
 *
 * @see ScriptRunner
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class ScriptTransformerConfig extends BaseDocHandlerConfig
        implements ChunkedTextSupport {

    private int maxReadSize = TextReader.DEFAULT_MAX_READ_SIZE;
    private Charset sourceCharset;
    private final TextMatcher fieldMatcher = new TextMatcher();

    private String engineName;
    private String script;

    /**
     * Gets source field matcher for fields on which to execute a script.
     * @return field matcher
     */
    @Override
    public TextMatcher getFieldMatcher() {
        return fieldMatcher;
    }

    /**
     * Sets source field matcher for fields on which to execute a script.
     * @param fieldMatcher field matcher
     */
    public ScriptTransformerConfig setFieldMatcher(
            TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }
}
