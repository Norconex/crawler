/* Copyright 2021-2026 Norconex Inc.
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

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.norconex.commons.lang.map.Properties;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.handler.DocHandlerException;
import com.norconex.importer.handler.ScriptRunner;
import com.norconex.importer.handler.condition.ConfigurableCondition;
import com.norconex.importer.util.chunk.ChunkedTextReader;
import com.norconex.importer.util.chunk.TextChunk;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

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
@ToString
@EqualsAndHashCode
@Slf4j
public class ScriptCondition
        implements ConfigurableCondition<ScriptConditionConfig> {

    //TODO consider using composition so most of the logic
    // can be shared with other ScriptXXX classes.

    //TODO add testing XML config in various unit tests

    @Getter
    private final ScriptConditionConfig configuration =
            new ScriptConditionConfig();

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    private ScriptRunner<Object> scriptRunner;

    @Override
    public boolean test(DocHandlerContext docCtx) throws IOException {

        var matches = new AtomicBoolean();
        ChunkedTextReader.builder()
                .charset(configuration.getSourceCharset())
                .fieldMatcher(configuration.getFieldMatcher())
                .maxChunkSize(configuration.getMaxReadSize())
                .build()
                .read(docCtx, chunk -> {
                    // only check if no successful match yet.
                    try {
                        if (!matches.get() && executeScript(docCtx, chunk)) {
                            matches.set(true);
                        }
                    } catch (DocHandlerException e) {
                        throw new IOException(e);
                    }
                    return true;
                });
        return matches.get();
    }

    private boolean executeScript(DocHandlerContext docCtx, TextChunk chunk)
            throws DocHandlerException {
        var returnValue = scriptRunner().eval(b -> {
            b.put("reference", docCtx.reference());
            b.put("content", chunk.getText());
            b.put("metadata", docCtx.metadata());
            b.put("parsed", docCtx.parseState());
            b.put("sectionIndex", chunk.getChunkIndex());
        });
        LOG.debug("Returned value from ScriptCondition: {}", returnValue);
        if (returnValue == null) {
            return false;
        }
        if (returnValue instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(returnValue.toString());
    }

    private synchronized ScriptRunner<Object> scriptRunner()
            throws DocHandlerException {
        if (scriptRunner == null) {
            var engineName = configuration.getEngineName();
            if (StringUtils.isBlank(configuration.getScript())) {
                throw new DocHandlerException("No script provided.");
            }
            if (StringUtils.isBlank(engineName)) {
                LOG.info(
                        "Script engine name not specified, defaulting to "
                                + ScriptRunner.JAVASCRIPT_ENGINE);
                engineName = ScriptRunner.JAVASCRIPT_ENGINE;
            }
            scriptRunner =
                    new ScriptRunner<>(engineName, configuration.getScript());
        }
        return scriptRunner;
    }
}
