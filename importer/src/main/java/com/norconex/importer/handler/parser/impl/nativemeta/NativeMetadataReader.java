/* Copyright 2023-2026 Norconex Inc.
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
package com.norconex.importer.handler.parser.impl.nativemeta;

import java.io.IOException;

import org.apache.tika.io.TikaInputStream;

import com.norconex.commons.lang.file.ContentType;

/**
 * Reads the metadata a document holds under the names its author gave
 * them, before the parser that extracts its text has renamed, merged or
 * dropped any of it. A reader looks only at the part of the document
 * that holds metadata, never at the whole of it.
 */
public interface NativeMetadataReader {

    /**
     * Whether this reader handles documents of that content type.
     * @param contentType the document content type
     * @return {@code true} if supported
     */
    boolean supports(ContentType contentType);

    /**
     * Reads the metadata. The stream is left where it was found, ready for
     * the parser: implementations only mark and reset it. The result also
     * says what to take out of the parser's own metadata afterwards, being
     * the entries that stand for fields returned here under their own names.
     * Anything the parser worked out by itself is left alone.
     * @param in the document, positioned at its start
     * @param charset the charset already detected for the document, if any
     * @return what was found
     * @throws IOException problem reading the document
     */
    NativeFields read(TikaInputStream in, String charset) throws IOException;
}
