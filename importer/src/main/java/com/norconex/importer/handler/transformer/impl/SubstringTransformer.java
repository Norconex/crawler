/* Copyright 2017-2024 Norconex Inc.
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
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

import org.apache.commons.io.IOUtils;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;

import lombok.Data;

/**
 * <p>Keep a substring of the content matching a begin and end character
 * indexes.
 * Useful when you have to
 * truncate long content, or when you know precisely where is located
 * the text to extract in some files.
 * </p>
 * <p>
 * The "begin" value is inclusive, while the "end" value
 * is exclusive.  Both are optional.  When not specified (or a negative value),
 * the index
 * is assumed to be the beginning and end of the content, respectively.
 * </p>
 * <p>
 * This class can be used as a pre-parsing (text content-types only)
 * or post-parsing handlers.</p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.transformer.impl.SubstringTransformer"
 *     {@nx.include com.norconex.importer.handler.transformer.AbstractCharStreamTransformer#attributes}
 *     begin="(number)" end="(number)">
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="SubstringTransformer" end="10000"/>
 * }
 * <p>
 * The above example truncates long text to be 10,000 characters maximum.
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
public class SubstringTransformer
        implements ConfigurableDocHandler<SubstringTransformerConfig> {

    private final SubstringTransformerConfig configuration =
            new SubstringTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {

        if (configuration.getFieldMatcher().isSet()) {
            // Fields
            for (Entry<String, List<String>> en : docCtx.metadata().matchKeys(
                    configuration.getFieldMatcher()).entrySet()) {
                var fld = en.getKey();
                var values = en.getValue();
                List<String> newVals = new ArrayList<>();
                for (String val : values) {
                    try (var input = new StringReader(val);
                            var output = new StringWriter()) {
                        doSubstring(input, output);
                        newVals.add(output.toString());
                    }
                }
                docCtx.metadata().replace(fld, newVals);
            }
        } else {
            // Body
            try (var input = docCtx.input().asReader(
                    configuration.getSourceCharset());
                    var output = docCtx.output().asWriter(
                            configuration.getSourceCharset())) {
                doSubstring(input, output);
            }
        }
        return true;
    }

    private void doSubstring(Reader input, Writer output)
            throws IOException {
        long length = -1;
        if (configuration.getEnd() > -1) {
            if (configuration.getEnd() < configuration.getBegin()) {
                throw new IOException(
                        "\"end\" cannot be smaller than \"begin\" "
                                + "(begin:" + configuration.getBegin()
                                + "; end:" + configuration.getEnd());
            }
            length = configuration.getEnd()
                    - Math.max(configuration.getBegin(), 0);
        }
        if (length == 0) {
            output.write("");
        } else {
            IOUtils.copyLarge(input, output, configuration.getBegin(), length);
        }
    }
}
