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

import com.norconex.importer.handler.splitter.BaseDocumentSplitterConfig;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * Splits a document's text into chunks sized for embedding models, breaking
 * at the cleanest boundary available (paragraph, then sentence, then word)
 * rather than cutting mid-word at a fixed offset.
 * </p>
 *
 * @see <a href="https://crawler.norconex.com/docs/reference/importer/TextChunkSplitter">
 *      TextChunkSplitter configuration reference</a>
 */
@Data
@Accessors(chain = true)
public class TextChunkSplitterConfig extends BaseDocumentSplitterConfig {

    public static final int DEFAULT_MAX_CHUNK_SIZE = 1000;
    public static final int DEFAULT_CHUNK_OVERLAP = 100;
    public static final String DEFAULT_REFERENCE_CHUNK_PREFIX = "#chunk";

    /**
     * The maximum number of characters a chunk should have. A chunk may be
     * shorter when a clean break (paragraph, sentence, or word) is found
     * before this limit. Must be at least 1. Default is
     * {@value #DEFAULT_MAX_CHUNK_SIZE}.
     */
    private int maxChunkSize = DEFAULT_MAX_CHUNK_SIZE;

    /**
     * The number of characters from the end of a chunk to repeat at the
     * start of the next one, so a passage spanning a chunk boundary is not
     * lost from either side's context. Default is
     * {@value #DEFAULT_CHUNK_OVERLAP}. Set to 0 to disable.
     */
    private int chunkOverlap = DEFAULT_CHUNK_OVERLAP;

    /**
     * String to append to the parent document reference to form each
     * chunk's reference, followed by the chunk number (1-based). Blank
     * means the default, {@value #DEFAULT_REFERENCE_CHUNK_PREFIX}.
     */
    private String referenceChunkPrefix = DEFAULT_REFERENCE_CHUNK_PREFIX;

    // Unlike other splitters, the original is discarded by default: a
    // document long enough to be chunked is, by definition, too long for
    // the embedding step that follows, so passing it on alongside its
    // chunks would just make that step fail. Documents short enough to fit
    // in one chunk are not split at all, so they still pass through as-is.
    public TextChunkSplitterConfig() {
        setDiscardOriginal(true);
    }
}
