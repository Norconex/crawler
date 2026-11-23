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
import java.io.InputStream;
import java.util.List;

import org.apache.tika.io.TikaInputStream;

import com.norconex.commons.lang.file.ContentType;

import lombok.extern.slf4j.Slf4j;

/**
 * Finds the readers of native metadata that apply to a document, and runs
 * them before the parser that extracts the text does.
 */
@Slf4j
public final class NativeMetadata {

    private static final List<NativeMetadataReader> READERS =
            List.of(
                    new HtmlNativeMetadataReader(),
                    new EmailNativeMetadataReader(),
                    new PdfNativeMetadataReader(),
                    new OoxmlNativeMetadataReader(),
                    new OdfNativeMetadataReader(),
                    new Ole2NativeMetadataReader(),
                    new RtfNativeMetadataReader(),
                    new EpubNativeMetadataReader(),
                    new Mp3NativeMetadataReader(),
                    new ImageNativeMetadataReader());

    private NativeMetadata() {
    }

    /**
     * Reads the native metadata of a document, when a reader handles its
     * type. The stream is left where it was.
     * @param in the document, positioned at its start
     * @param contentType the document content type
     * @param charset the charset already detected, if any
     * @return what was found, never {@code null}
     */
    public static NativeFields read(
            TikaInputStream in, ContentType contentType, String charset) {
        if (contentType == null) {
            return NativeFields.none();
        }
        for (var reader : READERS) {
            if (reader.supports(contentType)) {
                try {
                    return reader.read(in, charset);
                } catch (IOException | RuntimeException e) {
                    LOG.warn("Could not read native metadata of {} "
                            + "document: {}", contentType, e.toString());
                    LOG.debug("Native metadata read failure.", e);
                }
            }
        }
        return NativeFields.none();
    }

    /**
     * Reads up to a number of bytes from the start of a stream, and puts it
     * back as it was.
     * @param in the stream
     * @param max the most to read
     * @return the bytes read, or {@code null} if the stream cannot be put back
     * @throws IOException problem reading
     */
    static byte[] peek(InputStream in, int max) throws IOException {
        if (!in.markSupported()) {
            LOG.debug("Stream does not support mark: no native metadata.");
            return null;
        }
        in.mark(max + 1);
        try {
            return in.readNBytes(max);
        } finally {
            in.reset();
        }
    }
}
