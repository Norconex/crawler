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

import com.norconex.importer.handler.BaseDocHandlerConfig;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * Calls an embeddings API and stores the resulting vector as a document
 * field. Targets the "OpenAI-compatible" <code>/embeddings</code> request
 * and response shape, which is also spoken by Ollama, self-hosted embedding
 * servers such as vLLM, and proxies such as LiteLLM that front many
 * providers behind that same shape — so this one handler works with all of
 * them without provider-specific code, by pointing {@link #getApiUrl()} at
 * whichever you run. The crawler calls the service you choose; it does not
 * run an embedding model itself.
 * </p>
 *
 * <p>
 * Embeds the document's body (its text content at the point this handler
 * runs), so it should run post-parse — after
 * {@link com.norconex.importer.handler.splitter.impl.TextChunkSplitter} if
 * documents are being chunked first, which is recommended for anything
 * longer than a paragraph or two.
 * </p>
 *
 * <p>
 * The vector is stored as a multi-valued field (one value per dimension).
 * For Elasticsearch and OpenSearch, set the committer's
 * <code>jsonFieldsPattern</code> to match the target field so the values
 * are sent as JSON numbers instead of quoted strings. Solr's
 * <code>DenseVectorField</code> accepts the values as they are sent.
 * </p>
 *
 * <p>
 * The crawler compares a document's content checksum with the previous crawl
 * only after the importer has run, so a document it cannot recognize as
 * unchanged sooner (by default on the web crawler, through the
 * <code>Last-Modified</code> header, before download) is embedded again
 * before being found unchanged and left out of the commit.
 * </p>
 *
 * @see <a href="https://crawler.norconex.com/docs/reference/importer/TextEmbeddingTransformer">
 *      TextEmbeddingTransformer configuration reference</a>
 */
@Data
@Accessors(chain = true)
public class TextEmbeddingTransformerConfig extends BaseDocHandlerConfig {

    public static final String DEFAULT_TARGET_FIELD = "embedding";

    /**
     * The embeddings endpoint URL, e.g.
     * <code>https://api.openai.com/v1/embeddings</code> or, for a local
     * Ollama server, <code>http://localhost:11434/v1/embeddings</code>.
     */
    private String apiUrl;

    /**
     * The API key, sent as an <code>Authorization: Bearer</code> header.
     * Leave blank for servers that do not require one (e.g., a local
     * Ollama instance).
     */
    private String apiKey;

    /**
     * The embedding model name to request, e.g.
     * <code>text-embedding-3-small</code>.
     */
    private String model;

    /**
     * The metadata field the resulting vector is stored into. Blank means
     * the default, {@value #DEFAULT_TARGET_FIELD}.
     */
    private String targetField = DEFAULT_TARGET_FIELD;

    /**
     * How long to wait for the embeddings API to respond, in seconds. A
     * value below 1 means the default, {@value #DEFAULT_TIMEOUT_SECONDS}.
     */
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;

    private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
}
