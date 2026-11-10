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
package com.norconex.committer.solr;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.map.Properties;

@Timeout(30)
class SolrUpdateBatchTest {

    private static String json(SolrUpdateBatch b) {
        return new String(b.toBytes(), UTF_8);
    }

    @Test
    void testEmpty() {
        var b = new SolrUpdateBatch();
        assertThat(b.isEmpty()).isTrue();
        assertThat(json(b)).isEqualTo("{}");
    }

    @Test
    void testSingleAndMultiValues() {
        var p = new Properties();
        p.add("id", "1");
        p.add("tag", "a", "b");
        var b = new SolrUpdateBatch();
        b.add(p);
        assertThat(b.size()).isEqualTo(1);
        assertThat(json(b)).isEqualTo(
                "{\"add\":{\"doc\":{\"id\":\"1\",\"tag\":[\"a\",\"b\"]}}}");
    }

    @Test
    void testOperationsKeepTheirOrder() {
        var p1 = new Properties();
        p1.add("id", "1");
        var p2 = new Properties();
        p2.add("id", "2");
        var b = new SolrUpdateBatch();
        b.add(p1);
        b.delete("1");
        b.add(p2);
        assertThat(b.size()).isEqualTo(3);
        assertThat(json(b)).isEqualTo(
                "{\"add\":{\"doc\":{\"id\":\"1\"}},"
                        + "\"delete\":{\"id\":\"1\"},"
                        + "\"add\":{\"doc\":{\"id\":\"2\"}}}");
    }

    @Test
    void testEscaping() {
        var p = new Properties();
        p.add("t", "q\" b\\ \n\r\t \u0001 é 😀");
        var b = new SolrUpdateBatch();
        b.add(p);
        assertThat(json(b)).isEqualTo(
                "{\"add\":{\"doc\":{\"t\":\"q\\\" b\\\\ \\n\\r\\t \\u0001 é 😀\"}}}");
    }

    @Test
    void testLoneSurrogatesAreReplaced() {
        var p = new Properties();
        p.add("t", "a\ud800b");
        var b = new SolrUpdateBatch();
        b.add(p);
        assertThat(json(b)).contains("a�b");
    }

    @Test
    void testDeleteEscapesId() {
        var b = new SolrUpdateBatch();
        b.delete("http://x/\"y\"");
        assertThat(json(b))
                .isEqualTo("{\"delete\":{\"id\":\"http://x/\\\"y\\\"\"}}");
    }
}
