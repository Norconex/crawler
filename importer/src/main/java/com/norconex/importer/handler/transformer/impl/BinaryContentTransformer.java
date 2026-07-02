/* Copyright 2026 Norconex Inc.
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
import java.util.Base64;

import org.apache.commons.io.IOUtils;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.handler.DocHandlerException;

import lombok.Data;

/**
 * <p>
 * Captures the document's original, unparsed content and stores it,
 * Base64-encoded, in a metadata field. This is meant for consumers that
 * need access to the raw document bytes after parsing has taken place
 * (parsing normally discards the original content), such as a committer
 * uploading the original file content rather than the extracted text.
 * </p>
 * <p>
 * Must be used as a pre-parse handler: the document is still in its
 * original format at that point. Using it post-parse raises an error since
 * the original content is no longer available by then.
 * </p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.transformer.impl.BinaryContentTransformer">
 *   <fieldName>
 *     (Name of the metadata field to store the Base64-encoded content in.
 *      Default is "binaryContent".)
 *   </fieldName>
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="BinaryContentTransformer"/>
 * }
 */
@SuppressWarnings("javadoc")
@Data
public class BinaryContentTransformer
        implements DocHandler, Configurable<BinaryContentTransformerConfig> {

    private final BinaryContentTransformerConfig configuration =
            new BinaryContentTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {
        if (docCtx.parseState().isPost()) {
            throw new DocHandlerException(
                    "Document is already parsed. This handler must be used "
                            + "as a pre-parse handler.");
        }
        byte[] content;
        try (var input = docCtx.input().asInputStream()) {
            content = IOUtils.toByteArray(input);
        }
        docCtx.metadata().add(
                configuration.getFieldName(),
                Base64.getEncoder().encodeToString(content));
        return true;
    }
}
