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

import java.io.IOException;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BoundedInputStream;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.commons.lang.io.CachedInputStream;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;

import lombok.Data;

/**
 * <p>Adds the document length (i.e., number of bytes) to
 * the specified <code>field</code>. The length is the document
 * content length as it is in its current processing stage. If for
 * instance you set this tagger after a transformer that modifies the content,
 * the obtained length will be for the modified content, and not the
 * original length. To obtain a document's length before any modification
 * was made to it, use this tagger as one of the first
 * handler in your pre-parse handlers.</p>
 *
 * <h2>Storing values in an existing field</h2>
 * <p>
 * If a target field with the same name already exists for a document,
 * values will be added to the end of the existing value list.
 * It is possible to change this default behavior by supplying a
 * {@link PropertySetter}.
 * </p>
 *
 * <p>Can be used both as a pre-parse or post-parse handler.</p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.DocumentLengthTagger"
 *     toField="(mandatory target field)"
 *     {@nx.include com.norconex.commons.lang.map.PropertySetter#attributes}>
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="DocumentLengthTagger" toField="docSize" />
 * }
 *
 * <p>
 * The following stores the document lenght into a "docSize" field.
 * </p>
 */
@SuppressWarnings("javadoc")
@Data
public class DocumentLengthTransformer
        implements ConfigurableDocHandler<DocumentLengthTransformerConfig> {

    private final DocumentLengthTransformerConfig configuration =
            new DocumentLengthTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {

        if (StringUtils.isBlank(configuration.getToField())) {
            throw new IllegalArgumentException("\"toField\" cannot be empty.");
        }

        var length = -1L;
        var docIs = docCtx.input().asInputStream();
        if (docIs instanceof CachedInputStream cis) {
            length = cis.length();
        } else {
            var is = BoundedInputStream.builder().setInputStream(docIs).get();
            IOUtils.copy(is, NullOutputStream.INSTANCE);
            length = is.getCount();
        }

        PropertySetter.orAppend(configuration.getOnSet()).apply(
                docCtx.metadata(), configuration.getToField(), length);
        return true;
    }
}
