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
 * @see ExternalHandler
 * @see <a href="https://crawler.norconex.com/docs/reference/importer/ExternalParser">
 *      ExternalParser configuration reference</a>
 */

//TODO document it is the same as ExternalTransformer, but sets parse state
// to POST.
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
