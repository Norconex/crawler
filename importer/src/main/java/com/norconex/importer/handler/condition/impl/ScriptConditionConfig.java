/* Copyright 2021-2024 Norconex Inc.
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
package com.norconex.importer.handler.condition.impl;

import java.nio.charset.Charset;

import com.norconex.commons.lang.io.TextReader;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.handler.ScriptRunner;
import com.norconex.importer.handler.condition.BaseConditionConfig;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * A condition formulated using a scripting language.
 * </p>
 * <p>
 * Refer to {@link ScriptRunner} for more information on using a scripting
 * language with Norconex Importer.
 * </p>
 * <h2>How to create a condition with scripting:</h2>
 * <p>
 * The following are variables made available to your script for each
 * document:
 * </p>
 * <ul>
 *   <li><b>reference:</b> Document unique reference as a string.</li>
 *   <li><b>content:</b> Document content, as a string
 *       (of <code>maxReadSize</code> length).</li>
 *   <li><b>metadata:</b> Document metadata as a {@link Properties}
 *       object.</li>
 *   <li><b>parsed:</b> Whether the document was already parsed, as a
 *       boolean.</li>
 *   <li><b>sectionIndex:</b> Content section index (integer) if it had to be
 *       split because it was too large (as per <code>maxReadSize</code>).</li>
 * </ul>
 * <p>
 * The expected <b>return value</b> from your script is a boolean indicating
 * whether the document was matched or not.
 * </p>
 *
 * {@nx.xml.usage
 * <condition class="com.norconex.importer.handler.condition.impl.ScriptCondition"
 *   {@nx.include com.norconex.importer.handler.condition.AbstractStringCondition#attributes}
 *       engineName="(script engine name)">
 *   <script>(your script)</script>
 * </condition>
 * }
 *
 * <h4>Usage examples:</h4>
 *
 * <h5>JavaScript:</h5>
 * {@nx.xml
 * <condition class="ScriptCondition" engineName="JavaScript>
 *   <script><![CDATA[
 *     returnValue = metadata.getString('fruit') == 'apple'
 *             || content.indexOf('Apple') > -1;
 *   ]]></script>
 * </condition>
 * }
 *
 * <h5>Lua:</h5>
 * {@nx.xml
 * <condition class="ScriptCondition" engineName="lua">
 *   <script><![CDATA[
 *     returnValue = metadata:getString('fruit') == 'apple'
 *             or content:find('Apple') ~= nil;
 *   ]]></script>
 * </condition>
 * }
 *
 * <h5>Python:</h5>
 * {@nx.xml
 * <condition class="ScriptCondition" engineName="python">
 *   <script><![CDATA[
 *     returnValue = metadata.getString('fruit') == 'apple' \
 *             or content.__contains__('Apple');
 *   ]]></script>
 * </condition>
 * }
 *
 * <h5>Velocity:</h5>
 * {@nx.xml
 * <condition class="ScriptCondition" engineName="velocity">
 *   <script><![CDATA[
 *     #set($returnValue = $metadata.getString("fruit") == "apple"
 *             || $content.contains("Apple"))
 *   ]]></script>
 * </condition>
 * }
 *
 * @see ScriptRunner
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class ScriptConditionConfig extends BaseConditionConfig {

    private String engineName;
    private String script;
    private final TextMatcher fieldMatcher = new TextMatcher();

    /**
     * Gets this filter field matcher.
     * @return field matcher
     */
    public TextMatcher getFieldMatcher() {
        return fieldMatcher;
    }

    /**
     * Sets this condition field matcher.
     * @param fieldMatcher field matcher
     */
    public ScriptConditionConfig setFieldMatcher(TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }

    /**
     * The presumed source character encoding. Usually ignored and presumed
     * to be UTF-8 if the document has been parsed already.
     * @param sourceCharset character encoding of the source to be transformed
     * @return character encoding of the source to be transformed
     */
    private Charset sourceCharset;

    /**
     * The maximum number of characters to read at once, used for filtering.
     * Default is {@link TextReader#DEFAULT_MAX_READ_SIZE}.
     * @param maxReadSize maximum read size
     * @return maximum read size
     */
    private int maxReadSize = TextReader.DEFAULT_MAX_READ_SIZE;
}
