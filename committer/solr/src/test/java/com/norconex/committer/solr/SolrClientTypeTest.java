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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.committer.solr.SolrClientType.Mode;

@SuppressWarnings("deprecation")
@Timeout(30)
class SolrClientTypeTest {

    @Test
    void testModes() {
        assertThat(SolrClientType.HTTP2.getMode()).isEqualTo(Mode.SINGLE);
        assertThat(SolrClientType.HTTP_JDK.getMode()).isEqualTo(Mode.SINGLE);
        assertThat(SolrClientType.CONCURRENT_UPDATE_HTTP2.getMode())
                .isEqualTo(Mode.SINGLE);
        assertThat(SolrClientType.LB_HTTP2.getMode())
                .isEqualTo(Mode.LOAD_BALANCED);
        assertThat(SolrClientType.CLOUD.getMode()).isEqualTo(Mode.CLOUD);

        // deprecated ones
        assertThat(SolrClientType.HTTP.getMode()).isEqualTo(Mode.SINGLE);
        assertThat(SolrClientType.CONCURRENT_UPDATE.getMode())
                .isEqualTo(Mode.SINGLE);
        assertThat(SolrClientType.LB_HTTP.getMode())
                .isEqualTo(Mode.LOAD_BALANCED);
    }

    @Test
    void testHttp2Preference() {
        assertThat(SolrClientType.HTTP2.isHttp2()).isTrue();
        assertThat(SolrClientType.LB_HTTP2.isHttp2()).isTrue();
        assertThat(SolrClientType.HTTP.isHttp2()).isFalse();
        assertThat(SolrClientType.LB_HTTP.isHttp2()).isFalse();
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
}
