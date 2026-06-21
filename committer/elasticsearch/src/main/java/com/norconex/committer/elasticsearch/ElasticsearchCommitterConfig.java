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
package com.norconex.committer.elasticsearch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.norconex.committer.core.batch.BaseBatchCommitterConfig;
import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.commons.lang.security.Credentials;

import lombok.Data;
import lombok.NonNull;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ElasticsearchCommitterConfig extends BaseBatchCommitterConfig {

    public static final String DEFAULT_ELASTICSEARCH_CONTENT_FIELD =
            "content";
    public static final String DEFAULT_NODE = "http://localhost:9200";
    /** 1 second. */
    public static final Duration DEFAULT_CONNECTION_TIMEOUT =
            Duration.ofSeconds(1);
    /** 30 seconds. */
    public static final Duration DEFAULT_SOCKET_TIMEOUT =
            Duration.ofSeconds(30);

    /**
     * One or more Elasticsearch nodes to connect to.
     * Defaults to {@value ElasticsearchCommitterConfig#DEFAULT_NODE}
     */
    private final List<String> nodes = new ArrayList<>(List.of(DEFAULT_NODE));

    /**
     * The Elasticsearch index name.
     */
    private String indexName;

    /**
     * The type name. Type name is deprecated if you
     * are using Elasticsearch 7.0 or higher and should be {@code null}.
     */
    private String typeName;

    /**
     * Whether to ignore response errors.  By default, an exception is
     * thrown if the Elasticsearch response contains an error.
     * When <code>true</code> the errors are logged instead.
     */
    private boolean ignoreResponseErrors;

    /**
     * Whether automatic discovery of Elasticsearch cluster nodes should be
     * enabled.
     */
    private boolean discoverNodes;

    /**
     * Elasticsearch credentials, if applicable.
     */
    private final Credentials credentials = new Credentials();

    /**
     * The Base64-encoded API key for Elasticsearch API Key authentication.
     * When set, takes precedence over basic credentials.
     * The value should be the Base64-encoded API key as provided
     * by Elasticsearch (i.e., the value sent in the
     * {@code Authorization: ApiKey ...} header).
     */
    private String apiKey;

    /**
     * The character used to replace dots in field names.
     * Default is {@code null} (does not replace dots).
     */
    private String dotReplacement;

    /**
     * The regular expression matching fields that contains a JSON
     * object for its value (as opposed to a regular string).
     * Default is {@code null} (not matching any fields).
     */
    private String jsonFieldsPattern;

    /**
     * Elasticsearch connection timeout. Defaults to value of
     * {@link ElasticsearchCommitterConfig#DEFAULT_CONNECTION_TIMEOUT}
     */
    @NonNull
    private Duration connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;

    /**
     * Elasticsearch socket timeout.
     */
    @NonNull
    private Duration socketTimeout = DEFAULT_SOCKET_TIMEOUT;

    /**
     * Whether to fix IDs that are too long for Elasticsearch
     * ID limitation (512 bytes max). If <code>true</code>,
     * long IDs will be truncated and a hash code representing the
     * truncated part will be appended.
     */
    private boolean fixBadIds;

    /**
     * The document field name containing the value to be stored
     * in Elasticsearch "_id" field. Set to {@code null} to use the
     * document reference instead of a field (default).
     */
    private String sourceIdField;

    /**
     * The name of the Elasticsearch field where content will be stored.
     * Default is "content". A {@code null} value disables storing
     * the content.
     */
    private String targetContentField = DEFAULT_ELASTICSEARCH_CONTENT_FIELD;

    /**
     * Gets an unmodifiable list of Elasticsearch cluster node URLs.
     * Defaults to "http://localhost:9200".
     * @return Elasticsearch nodes
     */
    public List<String> getNodes() {
        return Collections.unmodifiableList(nodes);
    }

    /**
     * Sets cluster node URLs.
     * Node URLs with no port are assumed to be using port 80.
     * @param nodes Elasticsearch cluster nodes
     */
    public void setNodes(List<String> nodes) {
        CollectionUtil.setAll(this.nodes, nodes);
    }

    /**
     * Gets Elasticsearch authentication credentials.
     * @return credentials
     */
    public Credentials getCredentials() {
        return credentials;
    }

    /**
     * Sets Elasticsearch authentication credentials.
     * @param credentials the credentials
     */
    public void setCredentials(Credentials credentials) {
        this.credentials.copyFrom(credentials);
    }
}
