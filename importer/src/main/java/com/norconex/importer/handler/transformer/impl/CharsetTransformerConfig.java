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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.charset.Charset;

import com.norconex.commons.lang.text.TextMatcher;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * Transforms a document content (if needed) from a source character
 * encoding (charset) to a target one. Both the source and target character
 * encodings are optional. If no source character encoding is explicitly
 * provided, it first tries to detect the encoding of the document
 * content before converting it to the target encoding. If the source
 * character encoding cannot be established, the content encoding will remain
 * unchanged. When no target character encoding is specified, UTF-8 is assumed.
 * </p>
 *
 * <h2>Should I use this transformer?</h2>
 * <p>
 * Before using this transformer, you need to know the parsing of documents
 * by the importer using default document parser factory will try to convert
 * and return content as UTF-8 (for most, if not all content-types).
 * If UTF-8 is your desired target, it only make sense to use this transformer
 * as a pre-parsing handler (for text content-types only) when it is important
 * to work with a specific character encoding before parsing.
 * If on the other hand you wish to convert to a character encoding to a
 * target different than UTF-8, you can use this transformer as a post-parsing
 * handler to do so.
 * </p>
 *
 * <h2>Conversion is not flawless</h2>
 * <p>
 * Because character encoding detection is not always accurate and because
 * documents sometime mix different encoding, there is no guarantee this
 * class will handle ALL character encoding conversions properly.
 * </p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.transformer.impl.CharsetTransformer"
 *     sourceCharset="(character encoding)"
 *     targetCharset="(character encoding)">
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 * </handler>
 * }
 *
 *
 * {@nx.xml.example
 * <handler class="CharsetTransformer"
 *     sourceCharset="ISO-8859-1" targetCharset="UTF-8">
 * </handler>
 * }
 * <p>
 * The above example converts the content of a document from "ISO-8859-1"
 * to "UTF-8".
 * </p>
 *
 * @see CharsetTagger
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class CharsetTransformerConfig extends BaseDocHandlerConfig {

    public static final Charset DEFAULT_TARGET_CHARSET = UTF_8;

    private Charset targetCharset = DEFAULT_TARGET_CHARSET;
    private Charset sourceCharset;
    /**
     * Optional matcher of fields to convert the character set of their values.
     * When not specified, transformation is on the document content.
     * @param fieldMatcher field matcher
     * @return field matcher
     */
    private final TextMatcher fieldMatcher = new TextMatcher();

    /**
     * Set field matcher.
     * @param fieldMatcher field matcher
     */
    public void setFieldMatcher(TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
    }
}
