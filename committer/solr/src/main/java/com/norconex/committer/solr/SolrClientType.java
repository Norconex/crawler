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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.apache.solr.client.solrj.impl.LBSolrClient;
import org.apache.solr.client.solrj.jetty.ConcurrentUpdateJettySolrClient;
import org.apache.solr.client.solrj.jetty.HttpJettySolrClient;
import org.apache.solr.client.solrj.jetty.LBJettySolrClient;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Supported
 * <a href="https://lucene.apache.org/solr/guide/8_1/using-solrj.html#types-of-solrclients">
 * SolrClient</a> types.
 */
public enum SolrClientType {

    /**
     * For use with a SolrCloud cluster. Expects a comma-separated list
     * of Zookeeper hosts.
     */
    CLOUD("CloudSolrClient", url -> {
        List<String> urls = List.of(url.split(SolrClientType.CSV_SPLIT_REGEX));
        if (url.startsWith("http")) {
            return new CloudSolrClient.Builder(urls).build();
        }
        return new CloudSolrClient.Builder(urls, Optional.empty()).build();
    }),

    /**
     * For direct access to a single Solr node using the HTTP/2 protocol
     * (via Jetty). Ideal for local development or small setups.
     * Expects a Solr URL.
     */
    HTTP2("HttpJettySolrClient",
            url -> new HttpJettySolrClient.Builder(url).build()),

    /**
     * A client using the HTTP/2 protocol (via Jetty), performing simple
     * load-balancing as an alternative to an external load balancer.
     * Expects two or more Solr node URLs (comma-separated).
     */
    LB_HTTP2("LBJettySolrClient", url -> {
        var endpoints = Arrays.stream(url.split(SolrClientType.CSV_SPLIT_REGEX))
                .map(LBSolrClient.Endpoint::from)
                .toArray(LBSolrClient.Endpoint[]::new);
        return new LBJettySolrClient.Builder(
                new HttpJettySolrClient.Builder().build(), endpoints).build();
    }),

    /**
     * A client using the HTTP/2 protocol (via Jetty), optimized for mass
     * upload on a single node. Not best for queries.
     * Expects a Solr URL.
     */
    CONCURRENT_UPDATE_HTTP2("ConcurrentUpdateJettySolrClient",
            url -> new ConcurrentUpdateJettySolrClient.Builder(
                    url, new HttpJettySolrClient.Builder().build()).build()),

    /**
     * A client using the JDK’s built-in Http Client. Supports both Http/2
     * and Http/1.1. Supports async.
     */
    HTTP_JDK("HttpJdkSolrClient",
            url -> new HttpJdkSolrClient.Builder(url).build()),

    // Keep these deprecations for a while, for backward compatibility

    /**
     * For direct access to a single Solr node using the HTTP/1.x protocol.
     * Ideal for local development or small setups. Expects a Solr URL.
     * @deprecated use {@link #HTTP2} instead.
     */
    @Deprecated(since = "4.0.0")
    HTTP("HttpSolrClient",
            url -> new HttpJettySolrClient.Builder(url).build()),
    /**
     * A client using the HTTP/1.x protocol, performing simple load-balancing
     * as an alternative to an external load balancer.
     * Expects two or more Solr node URLs (comma-separated).
     * @deprecated use {@link #LB_HTTP2} instead.
     */
    @Deprecated(since = "4.0.0")
    LB_HTTP("LBHttpSolrClient", url -> {
        var endpoints = Arrays.stream(url.split(SolrClientType.CSV_SPLIT_REGEX))
                .map(LBSolrClient.Endpoint::from)
                .toArray(LBSolrClient.Endpoint[]::new);
        return new LBJettySolrClient.Builder(
                new HttpJettySolrClient.Builder().build(), endpoints).build();
    }),
    /**
     * A client using the HTTP/1.x protocol, optimized for mass upload on a
     * single node.  Not best for queries.
     * Expects a Solr URL.
     * @deprecated use {@link #CONCURRENT_UPDATE_HTTP2} instead.
     */
    @Deprecated(since = "4.0.0")
    CONCURRENT_UPDATE("ConcurrentUpdateSolrClient",
            url -> new ConcurrentUpdateJettySolrClient.Builder(
                    url, new HttpJettySolrClient.Builder().build()).build()),
                    ;

    private static final String CSV_SPLIT_REGEX = "\\s*,\\s*";

    private final String type;
    private final Function<String, SolrClient> clientFactory;

    SolrClientType(String type, Function<String, SolrClient> f) {
        this.type = type;
        clientFactory = f;
    }

    @JsonValue
    @Override
    public String toString() {
        return type;
    }

    public SolrClient create(String solrURL) {
        Objects.requireNonNull(solrURL, "'solrURL' must not be null.");
        return clientFactory.apply(solrURL);
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
