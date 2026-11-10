/* Copyright 2025-2026 Norconex Inc.
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
package com.norconex.crawler.core.cluster.impl.hazelcast;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import com.hazelcast.core.HazelcastInstance;
import com.norconex.commons.lang.file.ContentType;
import com.norconex.crawler.core.cluster.pipeline.PipelineStatus;
import com.norconex.crawler.core.cluster.pipeline.StepRecord;
import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.core.ledger.ProcessingStatus;

/**
 * Verifies that Compact serializers round-trip objects correctly through a
 * real (mock-network) Hazelcast instance.
 */
class CompactSerializerTest {

    private static HazelcastInstance hz;

    private static HazelcastInstance hz() {
        if (hz == null || !hz.getLifecycleService().isRunning()) {
            var cfg = HazelcastTestSupport.buildTestConfig();
            cfg.getSerializationConfig()
                    .getCompactSerializationConfig()
                    .addSerializer(new StepRecordCompactSerializer())
                    .addSerializer(
                            new JacksonCompactSerializer<>(CrawlerEntry.class));
            hz = HazelcastTestSupport.newInstance(cfg);
        }
        return hz;
    }

    @AfterAll
    static void cleanup() {
        if (hz != null && hz.getLifecycleService().isRunning()) {
            hz.shutdown();
        }
    }

    @Test
    void testStepRecordRoundTrip() {
        var original = new StepRecord()
                .setPipelineId("pipe-1")
                .setStepId("step-A")
                .setUpdatedAt(System.currentTimeMillis())
                .setStatus(PipelineStatus.RUNNING)
                .setRunId("run-xyz");

        var mapName = "test-step-" + UUID.randomUUID();
        var map = hz().<String, StepRecord>getMap(mapName);
        map.put("k1", original);

        var loaded = map.get("k1");
        assertThat(loaded.getPipelineId()).isEqualTo("pipe-1");
        assertThat(loaded.getStepId()).isEqualTo("step-A");
        assertThat(loaded.getUpdatedAt()).isEqualTo(original.getUpdatedAt());
        assertThat(loaded.getStatus()).isEqualTo(PipelineStatus.RUNNING);
        assertThat(loaded.getRunId()).isEqualTo("run-xyz");
    }

    @Test
    void testStepRecordNullStatus() {
        var original = new StepRecord()
                .setPipelineId("pipe-2")
                .setStepId(null)
                .setUpdatedAt(0L)
                .setStatus(null)
                .setRunId(null);

        var mapName = "test-step-null-" + UUID.randomUUID();
        var map = hz().<String, StepRecord>getMap(mapName);
        map.put("k1", original);

        var loaded = map.get("k1");
        assertThat(loaded.getPipelineId()).isEqualTo("pipe-2");
        assertThat(loaded.getStepId()).isNull();
        assertThat(loaded.getStatus()).isNull();
        assertThat(loaded.getRunId()).isNull();
    }

    @Test
    void testCrawlEntryRoundTrip() {
        var now = ZonedDateTime.now();
        var original = new CrawlerEntry("http://example.com/page1");
        original.setDepth(3);
        original.setProcessingStatus(ProcessingStatus.PROCESSED);
        original.setProcessingOutcome(ProcessingOutcome.NEW);
        original.setReferenceTrail(List.of("http://a.com", "http://b.com"));
        original.setMetaChecksum("meta123");
        original.setContentChecksum("content456");
        original.setQueuedAt(now);
        original.setProcessingAt(now.plusSeconds(1));
        original.setProcessedAt(now.plusSeconds(2));
        original.setLastModified(now.minusDays(1));
        original.setContentType(ContentType.HTML);
        original.setCharset(StandardCharsets.UTF_8);
        original.setOrphan(true);
        original.setDeleted(false);

        var mapName = "test-entry-" + UUID.randomUUID();
        var map = hz().<String, CrawlerEntry>getMap(mapName);
        map.put("k1", original);

        var loaded = map.get("k1");
        assertThat(loaded.getReference()).isEqualTo("http://example.com/page1");
        assertThat(loaded.getDepth()).isEqualTo(3);
        assertThat(loaded.getProcessingStatus())
                .isEqualTo(ProcessingStatus.PROCESSED);
        assertThat(loaded.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(loaded.getReferenceTrail())
                .containsExactly("http://a.com", "http://b.com");
        assertThat(loaded.getMetaChecksum()).isEqualTo("meta123");
        assertThat(loaded.getContentChecksum()).isEqualTo("content456");
        assertThat(loaded.getContentType()).isEqualTo(ContentType.HTML);
        assertThat(loaded.isOrphan()).isTrue();
        assertThat(loaded.isDeleted()).isFalse();
        // Jackson serializes ZonedDateTime — verify non-null
        assertThat(loaded.getQueuedAt()).isNotNull();
        assertThat(loaded.getProcessingAt()).isNotNull();
        assertThat(loaded.getProcessedAt()).isNotNull();
    }

    @Test
    void testCrawlEntryMinimal() {
        var original = new CrawlerEntry();

        var mapName = "test-entry-min-" + UUID.randomUUID();
        var map = hz().<String, CrawlerEntry>getMap(mapName);
        map.put("k1", original);

        var loaded = map.get("k1");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getReference()).isNull();
        assertThat(loaded.getProcessingStatus())
                .isEqualTo(ProcessingStatus.UNTRACKED);
    }
}
