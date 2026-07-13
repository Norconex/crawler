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
package com.norconex.importer.handler.parser.impl;

import java.io.IOException;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.handler.parser.ParseState;
import com.norconex.importer.handler.transformer.impl.ExternalTransformer;
import com.norconex.importer.handler.transformer.impl.ExternalTransformerConfig;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/*
 * <p>
 * Parses and extracts text from a file using an external application to do so.
 * </p>
 * <p>
 * This class relies on {@link ExternalHandler} for most of the work.
 * Refer to {@link ExternalHandler} for full documentation.
 * </p>
 * <p>
 * This parser can be made configurable via XML. See
 * {@link GenericDocumentParserFactory} for general indications how
 * to configure parsers.
 * </p>
 * <p>
 * To use an external application to change a file content after parsing has
 * already occurred, consider using {@link ExternalTransformer} instead.
 * </p>
 *
 * {@nx.xml.usage
 * <parser contentType="(content type this parser is associated to)"
 *     class="com.norconex.importer.parser.impl.ExternalParser" >
 *
 *   <command>
 *     c:\Apps\myapp.exe ${INPUT} ${OUTPUT} ${INPUT_META} ${OUTPUT_META} ${REFERENCE}
 *   </command>
 *
 *   <metadata
 *       inputFormat="[json|xml|properties]"
 *       outputFormat="[json|xml|properties]"
 *       {@nx.include com.norconex.commons.lang.map.PropertySetter#attributes}>
 *     <!-- pattern only used when no output format is specified -->
 *     <pattern {@nx.include com.norconex.commons.lang.text.RegexFieldValueExtractor#attributes}>
 *       (regular expression)
 *     </pattern>
 *     <!-- repeat pattern tag as needed -->
 *   </metadata>
 *
 *   <environment>
 *     <variable name="(environment variable name)">
 *       (environment variable value)
 *     </variable>
 *     <!-- repeat variable tag as needed -->
 *   </environment>
 *
 * </parser>
 * }
 *
 *
 * {@nx.xml.example
 * <parser contentType="text/plain"
 *     class="com.norconex.importer.parser.impl.ExternalParser" >
 *   <command>/path/transform/app ${INPUT} ${OUTPUT}</command>
 *   <metadata>
 *     <pattern field="docnumber" valueGroup="1">DocNo:(\d+)</pattern>
 *   </metadata>
 * </parser>
 * }
 *
 * <p>
 * The above example invokes an external application processing for
 * simple text files that accepts two files as arguments:
 * the first one being the file to
 * transform, the second one being holding the transformation result.
 * It also extract a document number from STDOUT, found as "DocNo:1234"
 * and storing it as "docnumber".
 * </p>
 *
 * @see ExternalHandler
 */

//TODO document it is the same as ExternalTransformer, but sets parse state
// to POST.
@SuppressWarnings("javadoc")
@Data
public class ExternalParser
        implements ConfigurableDocHandler<ExternalTransformerConfig> {

    //TODO what about conditionally disabling some parsers? already covered?

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @JsonIgnore
    private final ExternalTransformer t = new ExternalTransformer();
    private final ExternalTransformerConfig configuration =
            t.getConfiguration();

    @Override
    public boolean handle(DocHandlerContext ctx) throws IOException {
        t.handle(ctx);
        ctx.parseState(ParseState.POST);
        return true;
    }

}
