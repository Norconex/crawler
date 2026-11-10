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
 * The type of connection to establish with Solr. Names are the ones
 * historically used by the Solr committer (named after the SolrJ classes)
 * so existing configurations keep working. Each type maps to one of
 * three connection modes (see {@link Mode}).
 * </p>
 */
public enum SolrClientType {

    /**
     * For use with a SolrCloud cluster. Expects one or more Solr node URLs
     * (comma-separated), each ending with the collection name, e.g.,
     * <code>http://host1:8983/solr/mycollection</code>. The live nodes
     * hosting the collection are discovered over HTTP (no ZooKeeper
     * access required) and updates are spread across them.
     */
    CLOUD("CloudSolrClient", Mode.CLOUD, true),

    /**
     * For direct access to a single Solr node. Uses HTTP/2 over TLS when
     * the URL is "https" (HTTP/1.1 otherwise). Ideal for local development
     * or small setups. Expects a Solr URL.
     */
    HTTP2("HttpJettySolrClient", Mode.SINGLE, true),

    /**
     * Simple load-balancing as an alternative to an external load balancer.
     * Expects two or more Solr node URLs (comma-separated).
     */
    LB_HTTP2("LBJettySolrClient", Mode.LOAD_BALANCED, true),

    /**
     * Same as {@link #HTTP2}. Committers already send documents in batches,
     * so no separate concurrent mode is needed.
     */
    CONCURRENT_UPDATE_HTTP2("ConcurrentUpdateJettySolrClient",
            Mode.SINGLE, true),

    /**
     * Same as {@link #HTTP2}.
     */
    HTTP_JDK("HttpJdkSolrClient", Mode.SINGLE, true),

    // Keep these deprecations for a while, for backward compatibility

    /**
     * For direct access to a single Solr node, always using HTTP/1.1.
     * Expects a Solr URL.
     * @deprecated use {@link #HTTP2} instead.
     */
    @Deprecated(since = "4.0.0")
    HTTP("HttpSolrClient", Mode.SINGLE, false),

    /**
     * Simple load-balancing, always using HTTP/1.1.
     * Expects two or more Solr node URLs (comma-separated).
     * @deprecated use {@link #LB_HTTP2} instead.
     */
    @Deprecated(since = "4.0.0")
    LB_HTTP("LBHttpSolrClient", Mode.LOAD_BALANCED, false),

    /**
     * Same as {@link #HTTP}.
     * @deprecated use {@link #HTTP2} instead.
     */
    @Deprecated(since = "4.0.0")
    CONCURRENT_UPDATE("ConcurrentUpdateSolrClient", Mode.SINGLE, false);

    /**
     * How the Solr committer connects to Solr.
     */
    public enum Mode {
        /** A single Solr URL. */
        SINGLE,
        /** Several Solr URLs, with updates spread across them. */
        LOAD_BALANCED,
        /** Solr URLs of a SolrCloud cluster, with node discovery. */
        CLOUD
    }

    private final String type;
    private final Mode mode;
    private final boolean http2;

    SolrClientType(String type, Mode mode, boolean http2) {
        this.type = type;
        this.mode = mode;
        this.http2 = http2;
    }

    /**
     * Gets how this type connects to Solr.
     * @return connection mode
     */
    public Mode getMode() {
        return mode;
    }

    /**
     * Whether HTTP/2 is preferred (applies to "https" URLs only).
     * @return {@code true} if HTTP/2 is preferred
     */
    public boolean isHttp2() {
        return http2;
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
