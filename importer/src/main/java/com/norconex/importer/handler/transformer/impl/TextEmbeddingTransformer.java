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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.importer.handler.DocHandlerContext;

import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Calls an embeddings API and stores the resulting vector in a document
 * field. See {@link TextEmbeddingTransformerConfig} for the request shape
 * this targets and which providers speak it.
 * </p>
 *
 * @see <a href="https://crawler.norconex.com/docs/reference/importer/TextEmbeddingTransformer">
 *      TextEmbeddingTransformer configuration reference</a>
 */
@Data
@Slf4j
public class TextEmbeddingTransformer
        implements ConfigurableDocHandler<TextEmbeddingTransformerConfig> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VECTOR_DELIMITER = ",";

    private final TextEmbeddingTransformerConfig configuration =
            new TextEmbeddingTransformerConfig();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Getter(value = AccessLevel.NONE)
    @Setter(value = AccessLevel.NONE)
    private HttpClient client;

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {
        var text = docCtx.input().asString();
        if (StringUtils.isBlank(text)) {
            return true;
        }
        if (StringUtils.isBlank(configuration.getApiUrl())) {
            throw new IOException(
                    "Embeddings API URL is not configured (apiUrl).");
        }

        var embedding = embed(docCtx, text);
        // Blank means the default, for the same reason as the timeout below:
        // a set with an empty field name would silently store nowhere useful.
        docCtx.metadata().set(
                StringUtils.defaultIfBlank(
                        configuration.getTargetField(),
                        TextEmbeddingTransformerConfig.DEFAULT_TARGET_FIELD),
                embedding.toArray(new Double[0]));
        return true;
    }

    // Caches by a hash of the exact text plus the model and endpoint that
    // would embed it, so the same paragraph never pays for the same API call
    // twice — whether it recurs on a later crawl of an unchanged page, or as
    // boilerplate shared by many different pages in this same crawl. When no
    // real cache is supplied (e.g., the importer running standalone), this
    // still works: it just calls the API every time.
    private List<Double> embed(DocHandlerContext docCtx, String text)
            throws IOException {
        var cached = docCtx.cache().computeIfAbsent(
                cacheKey(text), key -> serialize(call(text)));
        return deserialize(cached);
    }

    private String cacheKey(String text) {
        return "TextEmbeddingTransformer|"
                + configuration.getApiUrl() + '|'
                + configuration.getModel() + '|'
                + DigestUtils.sha256Hex(text);
    }

    private static String serialize(List<Double> vector) {
        return vector.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(VECTOR_DELIMITER));
    }

    private static List<Double> deserialize(String serialized) {
        var values = new ArrayList<Double>();
        for (String value : StringUtils.split(serialized, VECTOR_DELIMITER)) {
            values.add(Double.valueOf(value));
        }
        return values;
    }

    private List<Double> call(String text) throws IOException {
        var requestBody = MAPPER.createObjectNode();
        requestBody.put("model", configuration.getModel());
        requestBody.put("input", text);

        var requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(configuration.getApiUrl()))
                .header("Content-Type", "application/json")
                .timeout(timeout())
                .POST(BodyPublishers.ofString(requestBody.toString()));
        if (StringUtils.isNotBlank(configuration.getApiKey())) {
            requestBuilder.header(
                    "Authorization", "Bearer " + configuration.getApiKey());
        }

        HttpResponse<String> response;
        try {
            response = ensureClient().send(
                    requestBuilder.build(), BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    "Interrupted while calling embeddings API: "
                            + configuration.getApiUrl(),
                    e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new IOException(String.format(
                    "Embeddings API at %s returned status %s: %s",
                    configuration.getApiUrl(), response.statusCode(),
                    StringUtils.abbreviate(response.body(), 500)));
        }

        return parseEmbedding(response.body());
    }

    // Parses the "OpenAI-compatible" response shape:
    // {"data":[{"embedding":[0.1, 0.2, ...], "index": 0, ...}], ...}
    private List<Double> parseEmbedding(String responseBody)
            throws IOException {
        var root = MAPPER.readTree(responseBody);
        var data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IOException(
                    "Embeddings API response had no \"data\" array: "
                            + StringUtils.abbreviate(responseBody, 500));
        }
        var vector = data.get(0).path("embedding");
        if (!vector.isArray() || vector.isEmpty()) {
            throw new IOException(
                    "Embeddings API response had no \"embedding\" array: "
                            + StringUtils.abbreviate(responseBody, 500));
        }
        var values = new ArrayList<Double>(vector.size());
        vector.forEach(node -> values.add(node.asDouble()));
        return values;
    }

    // HttpRequest rejects a zero or negative timeout outright, and config
    // editors that emit 0 for an untouched number make that easy to hit, so
    // anything below 1 means "use the default".
    private Duration timeout() {
        var seconds = configuration.getTimeoutSeconds();
        return Duration.ofSeconds(
                seconds > 0
                        ? seconds
                        : TextEmbeddingTransformerConfig.DEFAULT_TIMEOUT_SECONDS);
    }

    private synchronized HttpClient ensureClient() {
        if (client == null) {
            client = HttpClient.newBuilder()
                    .connectTimeout(timeout())
                    .build();
        }
        return client;
    }
}
