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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.norconex.crawler.core.cmd.crawl.pipeline.process.DocumentInProcess.TimeoutAction;
import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.ledger.CrawlerEntryLedger;

class DocumentInProcessTest {

    private DocumentInProcess doc;

    @AfterEach
    void tearDown() {
        if (doc != null) {
            doc.end();
        }
    }

    @Test
    void sendingIsAllowedWhenNoDocumentIsInProcess() {
        assertThat(DocumentInProcess.current()).isNull();
        assertThat(DocumentInProcess.maySend()).isTrue();
    }

    @Test
    void standaloneSendsWithoutAskingTheLedger() {
        var ledger = mock(CrawlerEntryLedger.class);
        doc = DocumentInProcess.begin(ledger, entry(), false);

        assertThat(DocumentInProcess.current()).isSameAs(doc);
        assertThat(DocumentInProcess.maySend()).isTrue();
        verify(ledger, never()).markSending(any());
    }

    @Test
    void clusteredMarksSendingOnceThenSends() {
        var ledger = mock(CrawlerEntryLedger.class);
        when(ledger.markSending(any())).thenReturn(true);
        doc = DocumentInProcess.begin(ledger, entry(), true);

        assertThat(DocumentInProcess.maySend()).isTrue();
        assertThat(DocumentInProcess.maySend()).isTrue();
        verify(ledger, times(1)).markSending(any());
    }

    @Test
    void clusteredDocumentGivenToAnotherNodeIsNeitherSentNorRecorded() {
        var ledger = mock(CrawlerEntryLedger.class);
        when(ledger.markSending(any())).thenReturn(false);
        doc = DocumentInProcess.begin(ledger, entry(), true);

        assertThat(DocumentInProcess.maySend()).isFalse();
        assertThat(DocumentInProcess.maySend()).isFalse();
        assertThat(doc.mayRecord()).isFalse();
        assertThat(doc.startRecordingOutcome()).isFalse();
        // Nothing to record on timing out: the other node does.
        assertThat(doc.timeOut()).isEqualTo(TimeoutAction.STOP_SENDING);
    }

    @Test
    void recordedOutcomeConfirmsHoldingForLaterSends() {
        var ledger = mock(CrawlerEntryLedger.class);
        doc = DocumentInProcess.begin(ledger, entry(), true);

        assertThat(doc.startRecordingOutcome()).isTrue();
        doc.outcomeRecorded(true);
        // Children sent after the parent's outcome: no ledger check.
        assertThat(DocumentInProcess.maySend()).isTrue();
        verify(ledger, never()).markSending(any());
    }

    @Test
    void outcomeNotRecordedMeansLost() {
        doc = DocumentInProcess.begin(
                mock(CrawlerEntryLedger.class), entry(), true);
        doc.startRecordingOutcome();
        doc.outcomeRecorded(false);

        assertThat(doc.mayRecord()).isFalse();
        assertThat(DocumentInProcess.maySend()).isFalse();
    }

    @Test
    void timingOutStopsSendingAndRecording() {
        doc = DocumentInProcess.begin(
                mock(CrawlerEntryLedger.class), entry(), false);

        assertThat(doc.timeOut()).isEqualTo(TimeoutAction.RECORD_ERROR);
        assertThat(doc.isTimedOut()).isTrue();
        assertThat(doc.timeOut()).isEqualTo(TimeoutAction.NONE);
        assertThat(DocumentInProcess.maySend()).isFalse();
        assertThat(doc.mayRecord()).isFalse();
        assertThat(doc.startRecordingOutcome()).isFalse();
    }

    @Test
    void timingOutWhileRecordingOnlyStopsSending() {
        doc = DocumentInProcess.begin(
                mock(CrawlerEntryLedger.class), entry(), false);
        doc.startRecordingOutcome();

        assertThat(doc.timeOut()).isEqualTo(TimeoutAction.STOP_SENDING);
    }

    @Test
    void endUnbindsOnlyItself() {
        doc = DocumentInProcess.begin(
                mock(CrawlerEntryLedger.class), entry(), false);
        assertThat(doc.isFor("ref-a")).isTrue();
        assertThat(doc.getReference()).isEqualTo("ref-a");
        doc.end();
        assertThat(DocumentInProcess.current()).isNull();
    }

    private static CrawlerEntry entry() {
        var entry = new CrawlerEntry("ref-a");
        entry.setProcessingClaim("claim-1");
        return entry;
    }
}
