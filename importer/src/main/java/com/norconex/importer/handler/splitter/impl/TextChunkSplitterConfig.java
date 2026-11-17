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
package com.norconex.importer.handler.splitter.impl;

import com.norconex.importer.handler.BaseDocHandlerConfig;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * Splits a document's text into chunks sized for embedding models, breaking
 * at the cleanest boundary available (paragraph, then sentence, then word)
 * rather than cutting mid-word at a fixed offset.
 * </p>
 *
 * <p>
 * <b>An exception among splitters.</b> The other splitters extend
 * {@link com.norconex.importer.handler.splitter.BaseDocumentSplitterConfig}
 * and keep the original document unless told to discard it
 * ({@code discardOriginal}, {@code false} by default). This one does the
 * opposite: the original is discarded once it has been split, because a
 * document long enough to be chunked is too long for the embedding step that
 * follows, so passing it on alongside its chunks would only make that step
 * fail. Rather than a {@code discardOriginal} that is {@code true} by
 * default, which would make this the one boolean of the crawler's
 * configuration not to default to {@code false}, it has {@link #isKeepOriginal()},
 * {@code false} by default. That is why this class does not extend
 * {@code BaseDocumentSplitterConfig}.
 * </p>
 *
 * @see <a href="https://crawler.norconex.com/docs/reference/importer/TextChunkSplitter">
 *      TextChunkSplitter configuration reference</a>
 */
@Data
@Accessors(chain = true)
public class TextChunkSplitterConfig extends BaseDocHandlerConfig {

    public static final int DEFAULT_MAX_CHUNK_SIZE = 1000;
    public static final int DEFAULT_CHUNK_OVERLAP = 100;
    public static final String DEFAULT_REFERENCE_CHUNK_PREFIX = "#chunk";

    /**
     * The maximum number of characters a chunk should have, the overlap
     * with the previous chunk included. A chunk may be shorter when a clean
     * break (paragraph, sentence, or word) is found before this limit. Must
     * be at least 1. Default is
     * {@value #DEFAULT_MAX_CHUNK_SIZE}.
     */
    private int maxChunkSize = DEFAULT_MAX_CHUNK_SIZE;

    /**
     * The number of characters from the end of a chunk to repeat at the
     * start of the next one, so a passage spanning a chunk boundary is not
     * lost from either side's context. The overlap counts toward
     * {@link #getMaxChunkSize()}, and must not be more than half of it.
     * Default is
     * {@value #DEFAULT_CHUNK_OVERLAP}. Set to 0 to disable.
     */
    private int chunkOverlap = DEFAULT_CHUNK_OVERLAP;

    /**
     * String to append to the parent document reference to form each
     * chunk's reference, followed by the chunk number (1-based). Blank
     * means the default, {@value #DEFAULT_REFERENCE_CHUNK_PREFIX}.
     */
    private String referenceChunkPrefix = DEFAULT_REFERENCE_CHUNK_PREFIX;

    /**
     * Whether to keep the original document, next to its chunks, once it was
     * split. Only applies if the document was actually split: one short
     * enough to fit in a single chunk is never split and always passes
     * through as it is.
     * <p>
     * By default the original is <b>not</b> kept: it is discarded in favor of
     * its chunks. This is the reverse of what the other splitters do (see this
     * class' description for why), and is why the property is a "keep" that is
     * {@code false} by default, and not a "discard" that would be
     * {@code true}.
     * </p>
     * @param keepOriginal <code>true</code> to keep the original document
     *     after an effective split
     * @return <code>true</code> when keeping the original document after an
     *     effective split, <code>false</code> (the default) when discarding it
     */
    private boolean keepOriginal;
}
