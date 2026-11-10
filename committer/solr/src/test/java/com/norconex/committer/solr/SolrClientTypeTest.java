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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.committer.core.CommitterException;

/**
 * Tests the deprecated settings and how connection settings are resolved.
 */
@SuppressWarnings("deprecation")
@Timeout(30)
class SolrClientTypeTest {

    @Test
    void testLegacyTypesTranslation() {
        assertThat(SolrClientType.HTTP2.getConnectionMode())
                .isEqualTo(SolrConnectionMode.SINGLE_NODE);
        assertThat(SolrClientType.HTTP_JDK.getConnectionMode())
                .isEqualTo(SolrConnectionMode.SINGLE_NODE);
        assertThat(SolrClientType.CONCURRENT_UPDATE_HTTP2.getConnectionMode())
                .isEqualTo(SolrConnectionMode.SINGLE_NODE);
        assertThat(SolrClientType.LB_HTTP2.getConnectionMode())
                .isEqualTo(SolrConnectionMode.LOAD_BALANCED);
        assertThat(SolrClientType.CLOUD.getConnectionMode())
                .isEqualTo(SolrConnectionMode.CLOUD);

        // HTTP/1.x ones
        assertThat(SolrClientType.HTTP.getHttpVersion())
                .isEqualTo(SolrHttpVersion.HTTP_1_1);
        assertThat(SolrClientType.LB_HTTP.getConnectionMode())
                .isEqualTo(SolrConnectionMode.LOAD_BALANCED);
        assertThat(SolrClientType.LB_HTTP.getHttpVersion())
                .isEqualTo(SolrHttpVersion.HTTP_1_1);
        assertThat(SolrClientType.CONCURRENT_UPDATE.getConnectionMode())
                .isEqualTo(SolrConnectionMode.SINGLE_NODE);
        assertThat(SolrClientType.HTTP2.getHttpVersion())
                .isEqualTo(SolrHttpVersion.AUTO);
    }

    @Test
    void testOf() {
        // names from the SolrJ days must still be recognized
        assertThat(SolrClientType.of("HttpJettySolrClient"))
                .isSameAs(SolrClientType.HTTP2);
        assertThat(SolrClientType.of("cloudsolrclient"))
                .isSameAs(SolrClientType.CLOUD);
        assertThat(SolrClientType.of("LBHttpSolrClient"))
                .isSameAs(SolrClientType.LB_HTTP);
        assertThat(SolrClientType.LB_HTTP2).hasToString("LBJettySolrClient");
        assertThat(SolrClientType.of(null)).isNull();
        assertThat(SolrClientType.of("nope")).isNull();
    }

    @Test
    void testDefaultsAreInferred() throws CommitterException {
        var cfg = new SolrCommitterConfig();
        cfg.setNodes(List.of("http://a/solr/c/"));
        var conn = SolrHttpClient.resolve(cfg);
        assertThat(conn.mode()).isEqualTo(SolrConnectionMode.SINGLE_NODE);
        assertThat(conn.httpVersion()).isEqualTo(SolrHttpVersion.AUTO);
        // trailing slash removed
        assertThat(conn.nodes()).containsExactly("http://a/solr/c");

        cfg.setNodes(List.of("http://a/solr/c", "http://b/solr/c"));
        assertThat(SolrHttpClient.resolve(cfg).mode())
                .isEqualTo(SolrConnectionMode.LOAD_BALANCED);
    }

    @Test
    void testExplicitSettingsWin() throws CommitterException {
        var cfg = new SolrCommitterConfig();
        cfg.setNodes(List.of("http://a/solr/c", "http://b/solr/c"));
        cfg.setConnectionMode(SolrConnectionMode.CLOUD);
        cfg.setHttpVersion(SolrHttpVersion.HTTP_2);
        cfg.setSolrClientType(SolrClientType.LB_HTTP);
        var conn = SolrHttpClient.resolve(cfg);
        assertThat(conn.mode()).isEqualTo(SolrConnectionMode.CLOUD);
        assertThat(conn.httpVersion()).isEqualTo(SolrHttpVersion.HTTP_2);
    }

    @Test
    void testDeprecatedSettingsStillWork() throws CommitterException {
        var cfg = new SolrCommitterConfig();
        cfg.setSolrURL("http://a/solr/c , http://b/solr/c");
        cfg.setSolrClientType(SolrClientType.LB_HTTP);
        var conn = SolrHttpClient.resolve(cfg);
        assertThat(conn.nodes())
                .containsExactly("http://a/solr/c", "http://b/solr/c");
        assertThat(conn.mode()).isEqualTo(SolrConnectionMode.LOAD_BALANCED);
        assertThat(conn.httpVersion()).isEqualTo(SolrHttpVersion.HTTP_1_1);

        // nodes take precedence over the deprecated URL
        cfg.setNodes(List.of("http://c/solr/c"));
        assertThat(SolrHttpClient.resolve(cfg).nodes())
                .containsExactly("http://c/solr/c");
    }

    @Test
    void testInvalidSettings() {
        var cfg = new SolrCommitterConfig();
        assertThatExceptionOfType(CommitterException.class)
                .isThrownBy(() -> SolrHttpClient.resolve(cfg))
                .withMessageContaining("nodes");

        cfg.setNodes(List.of("http://a/solr/c", "http://b/solr/c"));
        cfg.setConnectionMode(SolrConnectionMode.SINGLE_NODE);
        assertThatExceptionOfType(CommitterException.class)
                .isThrownBy(() -> SolrHttpClient.resolve(cfg))
                .withMessageContaining("SINGLE_NODE");

        cfg.setNodes(List.of("zk1:2181"));
        cfg.setConnectionMode(SolrConnectionMode.CLOUD);
        assertThatExceptionOfType(CommitterException.class)
                .isThrownBy(() -> SolrHttpClient.resolve(cfg))
                .withMessageContaining("ZooKeeper");
    }
}
