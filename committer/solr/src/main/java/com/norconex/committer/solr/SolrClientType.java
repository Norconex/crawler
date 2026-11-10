/* Copyright 2019-2026 Norconex Inc.
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
package com.norconex.committer.solr;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * <p>
 * The legacy way of choosing how to connect to Solr. Names are those of the
 * SolrJ clients the Solr committer used to rely on. Each one now maps to a
 * {@link SolrConnectionMode} and a {@link SolrHttpVersion}.
 * </p>
 *
 * @deprecated use {@link SolrCommitterConfig#setConnectionMode} and
 *     {@link SolrCommitterConfig#setHttpVersion} instead.
 */
@Deprecated(since = "4.0.0")
public enum SolrClientType {

    /** Same as {@link SolrConnectionMode#CLOUD}. */
    CLOUD("CloudSolrClient", SolrConnectionMode.CLOUD, SolrHttpVersion.AUTO),

    /** Same as {@link SolrConnectionMode#SINGLE_NODE}. */
    HTTP2("HttpJettySolrClient",
            SolrConnectionMode.SINGLE_NODE, SolrHttpVersion.AUTO),

    /** Same as {@link SolrConnectionMode#LOAD_BALANCED}. */
    LB_HTTP2("LBJettySolrClient",
            SolrConnectionMode.LOAD_BALANCED, SolrHttpVersion.AUTO),

    /** Same as {@link #HTTP2} (documents are already sent in batches). */
    CONCURRENT_UPDATE_HTTP2("ConcurrentUpdateJettySolrClient",
            SolrConnectionMode.SINGLE_NODE, SolrHttpVersion.AUTO),

    /** Same as {@link #HTTP2}. */
    HTTP_JDK("HttpJdkSolrClient",
            SolrConnectionMode.SINGLE_NODE, SolrHttpVersion.AUTO),

    /** Same as {@link #HTTP2}, but always using HTTP/1.1. */
    HTTP("HttpSolrClient",
            SolrConnectionMode.SINGLE_NODE, SolrHttpVersion.HTTP_1_1),

    /** Same as {@link #LB_HTTP2}, but always using HTTP/1.1. */
    LB_HTTP("LBHttpSolrClient",
            SolrConnectionMode.LOAD_BALANCED, SolrHttpVersion.HTTP_1_1),

    /** Same as {@link #HTTP}. */
    CONCURRENT_UPDATE("ConcurrentUpdateSolrClient",
            SolrConnectionMode.SINGLE_NODE, SolrHttpVersion.HTTP_1_1);

    private final String type;
    private final SolrConnectionMode connectionMode;
    private final SolrHttpVersion httpVersion;

    SolrClientType(
            String type,
            SolrConnectionMode connectionMode,
            SolrHttpVersion httpVersion) {
        this.type = type;
        this.connectionMode = connectionMode;
        this.httpVersion = httpVersion;
    }

    /**
     * Gets the connection mode this type translates to.
     * @return connection mode
     */
    public SolrConnectionMode getConnectionMode() {
        return connectionMode;
    }

    /**
     * Gets the HTTP version this type translates to.
     * @return HTTP version
     */
    public SolrHttpVersion getHttpVersion() {
        return httpVersion;
    }

    @JsonValue
    @Override
    public String toString() {
        return type;
    }

    @JsonCreator
    public static SolrClientType of(String type) {
        if (type == null) {
            return null;
        }
        for (SolrClientType t : SolrClientType.values()) {
            if (t.toString().equalsIgnoreCase(type)) {
                return t;
            }
        }
        return null;
    }
}
