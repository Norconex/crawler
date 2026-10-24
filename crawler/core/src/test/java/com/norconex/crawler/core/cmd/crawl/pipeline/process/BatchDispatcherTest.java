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
package com.norconex.crawler.core.cmd.crawl.pipeline.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.ledger.CrawlerEntryLedger;
import com.norconex.crawler.core.session.CrawlerSession;

@Timeout(30)
class BatchDispatcherTest {

    private final CrawlerSession session =
            mock(CrawlerSession.class, RETURNS_DEEP_STUBS);
    private final CrawlerEntryLedger ledger = mock(CrawlerEntryLedger.class);

    BatchDispatcherTest() {
        when(session.getCrawlContext().getCrawlEntryLedger())
                .thenReturn(ledger);
        when(session.getCluster().getLocalNode().getNodeName())
                .thenReturn("node1");
        when(session.getCluster().getNodeCount()).thenReturn(1);
    }

    private BatchDispatcher dispatcher() {
        return BatchDispatcher.builder()
                .maxBatchSize(10)
                .lowWatermark(2)
                .session(session)
                .maxReadAttempts(3)
                .readRetryPauseMs(1)
                .build();
    }

    @Test
    void testTransientQueueReadFailureIsRetried() throws Exception {
        var entry = mock(CrawlerEntry.class);
        when(ledger.getQueuedEntryCount())
                .thenThrow(new IllegalStateException("chunk not found"))
                .thenThrow(new IllegalStateException("chunk not found"))
                .thenReturn(1L);
        when(ledger.nextQueuedBatch(anyInt())).thenReturn(List.of(entry));

        assertThat(dispatcher().take()).isSameAs(entry);
        // two failed attempts, then a successful one that reads the count
        verify(ledger, atLeast(3)).getQueuedEntryCount();
    }

    @Test
    void testPersistentQueueReadFailureIsRethrownAfterMaxAttempts() {
        when(ledger.getQueuedEntryCount())
                .thenThrow(new IllegalStateException("store gone"));

        var dispatcher = dispatcher();
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(dispatcher::take)
                .withMessage("store gone");
        verify(ledger, times(3)).getQueuedEntryCount();
    }

    @Test
    void testEmptyQueueStillReturnsNull() throws Exception {
        when(ledger.getQueuedEntryCount()).thenReturn(0L);
        when(ledger.nextQueuedBatch(anyInt())).thenReturn(List.of());

        assertThat(dispatcher().take()).isNull();
    }
}
