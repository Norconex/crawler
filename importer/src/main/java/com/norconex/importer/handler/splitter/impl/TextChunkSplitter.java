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

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.io.TextReader;
import com.norconex.commons.lang.map.Properties;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.doc.DocMetaConstants;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.handler.DocHandlerException;
import com.norconex.importer.handler.splitter.AbstractDocumentSplitter;

import lombok.Data;

/**
 * <p>
 * Splits a document's text into chunks sized for embedding models. Meant to
 * run post-parse, on already-extracted plain text, so the same configuration
 * chunks a PDF, an HTML page, or a Word document alike, instead of every
 * format needing its own splitter.
 * </p>
 *
 * <p>
 * Each chunk is capped at {@link TextChunkSplitterConfig#getMaxChunkSize()}
 * characters, but the cut point within that limit is chosen to avoid
 * breaking content awkwardly: the last paragraph break is preferred, falling
 * back to the last sentence, then the last word, and only cutting mid-word
 * if none of those are found (see
 * {@link com.norconex.commons.lang.io.TextReader}). Consecutive chunks can
 * repeat a few trailing characters of the previous one via
 * {@link TextChunkSplitterConfig#getChunkOverlap()}, so a passage spanning
 * a chunk boundary is not lost from either side's context.
 * </p>
 *
 * <p>
 * A document short enough to fit in a single chunk is left untouched (no
 * child documents are created). A document that is split is discarded in
 * favor of its chunks, unless
 * {@link TextChunkSplitterConfig#isDiscardOriginal()} is turned off.
 * </p>
 *
 * @see <a href="https://crawler.norconex.com/docs/reference/importer/TextChunkSplitter">
 *      TextChunkSplitter configuration reference</a>
 */
@Data
public class TextChunkSplitter
        extends AbstractDocumentSplitter<TextChunkSplitterConfig> {

    public static final String DOC_CHUNK_INDEX = "document.chunk.index";
    public static final String DOC_CHUNK_COUNT = "document.chunk.count";

    private final TextChunkSplitterConfig configuration =
            new TextChunkSplitterConfig();

    @Override
    public void split(DocHandlerContext docCtx) throws DocHandlerException {
        // Must be checked up front: TextReader treats a size of 0 as "read
        // nothing" and keeps returning empty text without ever reaching the
        // end of the input, so a bad size would hang the crawl, not fail it.
        if (configuration.getMaxChunkSize() < 1) {
            throw new DocHandlerException(
                    "maxChunkSize must be at least 1, but was "
                            + configuration.getMaxChunkSize() + ".");
        }
        try {
            var text = docCtx.input().asString();
            if (StringUtils.isEmpty(text)) {
                return;
            }

            var rawChunks = readChunks(text);
            if (rawChunks.size() <= 1) {
                // Fits in one chunk already: not worth splitting.
                return;
            }

            var chunks = applyOverlap(rawChunks);
            var chunkDocs = docCtx.childDocs();
            for (var i = 0; i < chunks.size(); i++) {
                chunkDocs.add(toChunkDoc(docCtx, chunks.get(i), i,
                        chunks.size()));
            }
        } catch (IOException e) {
            throw new DocHandlerException(
                    "Could not split text into chunks: " + docCtx.reference(),
                    e);
        }
    }

    // Repeatedly asks TextReader for the next "clean" segment, which already
    // implements the paragraph/sentence/word fallback described in the class
    // javadoc — no need to reimplement that boundary logic here.
    private List<String> readChunks(String text) throws IOException {
        var chunks = new ArrayList<String>();
        try (var reader = new TextReader(
                new StringReader(text), configuration.getMaxChunkSize())) {
            String chunk;
            while ((chunk = reader.readText()) != null) {
                if (StringUtils.isNotEmpty(chunk)) {
                    chunks.add(chunk);
                }
            }
        }
        return chunks;
    }

    // Prepends the trailing overlap of each ORIGINAL (non-overlapped)
    // chunk to the next one, so overlap size does not compound across
    // many chunks.
    private List<String> applyOverlap(List<String> rawChunks) {
        var overlap = configuration.getChunkOverlap();
        if (overlap <= 0) {
            return rawChunks;
        }
        var result = new ArrayList<String>(rawChunks.size());
        for (var i = 0; i < rawChunks.size(); i++) {
            if (i == 0) {
                result.add(rawChunks.get(0));
                continue;
            }
            var previous = rawChunks.get(i - 1);
            var tail = previous.substring(
                    Math.max(0, previous.length() - overlap));
            result.add(tail + rawChunks.get(i));
        }
        return result;
    }

    private Doc toChunkDoc(
            DocHandlerContext docCtx, String chunkText, int index,
            int count) {
        // A blank prefix would yield references such as ".../page1", which
        // can collide with a real document. Config editors that emit empty
        // strings for untouched fields make that easy to hit, so blank means
        // "use the default" rather than "use nothing".
        var chunkRef = docCtx.reference()
                + StringUtils.defaultIfBlank(
                        configuration.getReferenceChunkPrefix(),
                        TextChunkSplitterConfig.DEFAULT_REFERENCE_CHUNK_PREFIX)
                + (index + 1);

        var chunkMeta = new Properties();
        chunkMeta.loadFromMap(docCtx.metadata());
        chunkMeta.set(
                DocMetaConstants.EMBEDDED_REFERENCE,
                Integer.toString(index + 1));
        chunkMeta.set(DOC_CHUNK_INDEX, index + 1);
        chunkMeta.set(DOC_CHUNK_COUNT, count);

        var chunkDoc = new Doc(chunkRef);
        chunkDoc.addParentReference(docCtx.reference());
        chunkDoc.setInputStream(
                docCtx.streamFactory().newInputStream(chunkText));
        chunkDoc.setMetadata(chunkMeta);
        return chunkDoc;
    }
}
