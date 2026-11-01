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
package com.norconex.crawler.core.doc.pipelines.importer.stages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.doc.CrawlerDocContext;
import com.norconex.crawler.core.doc.operations.DocumentConsumer;
import com.norconex.crawler.core.doc.pipelines.importer.ImporterPipelineContext;
import com.norconex.crawler.core.fetch.Fetcher;
import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.importer.doc.Doc;

/**
 * Tests for {@link DocumentPreProcessingStage}.
 */
@Timeout(30)
@SuppressWarnings("null")
class DocumentPreProcessingStageTest {

    private ImporterPipelineContext buildCtx(
            CrawlerSession session, String ref) {
        var entry = new CrawlerEntry(ref);
        var doc = new Doc(ref);
        var docContext = CrawlerDocContext.builder()
                .doc(doc)
                .currentCrawlEntry(entry)
                .build();
        return new ImporterPipelineContext(session, docContext);
    }

    private CrawlerSession buildSession(CrawlerConfig config, Fetcher fetcher) {
        var session = mock(CrawlerSession.class);
        var crawlContext = mock(CrawlerContext.class);
        when(session.getCrawlContext()).thenReturn(crawlContext);
        when(crawlContext.getCrawlConfig()).thenReturn(config);
        when(crawlContext.getFetcher()).thenReturn(fetcher);
        return session;
    }

    // -----------------------------------------------------------------
    // No consumers configured
    // -----------------------------------------------------------------

    @Test
    void noConsumers_returnsTrue() {
        var config = new CrawlerConfig();
        var session = buildSession(config, mock(Fetcher.class));
        var ctx = buildCtx(session, "http://example.com");

        assertThat(new DocumentPreProcessingStage().test(ctx)).isTrue();
    }

    @Test
    void noConsumers_noEventsFired() {
        var config = new CrawlerConfig();
        var session = buildSession(config, mock(Fetcher.class));
        var ctx = buildCtx(session, "http://example.com");

        new DocumentPreProcessingStage().test(ctx);

        verify(session, never()).fire(any());
    }

    // -----------------------------------------------------------------
    // With consumers
    // -----------------------------------------------------------------

    @Test
    void withOneConsumer_returnsTrueAndConsumerCalled() {
        var config = new CrawlerConfig();
        var fetcher = mock(Fetcher.class);
        var calledWith = new ArrayList<Doc>();
        DocumentConsumer consumer = (f, d) -> calledWith.add(d);
        config.setPreImportConsumers(List.of(consumer));

        var session = buildSession(config, fetcher);
        var ctx = buildCtx(session, "http://example.com");

        assertThat(new DocumentPreProcessingStage().test(ctx)).isTrue();
        assertThat(calledWith).hasSize(1);
        assertThat(calledWith.get(0).getReference())
                .isEqualTo("http://example.com");
    }

    @Test
    void withOneConsumer_firesOneEvent() {
        var config = new CrawlerConfig();
        DocumentConsumer consumer = (f, d) -> {};
        config.setPreImportConsumers(List.of(consumer));

        var session = buildSession(config, mock(Fetcher.class));
        var ctx = buildCtx(session, "http://example.com");

        new DocumentPreProcessingStage().test(ctx);

        verify(session, times(1)).fire(any());
    }

    @Test
    void withMultipleConsumers_allCalledAndEventsFiredForEach() {
        var config = new CrawlerConfig();
        var callCount = new int[] { 0 };
        DocumentConsumer c1 = (f, d) -> callCount[0]++;
        DocumentConsumer c2 = (f, d) -> callCount[0]++;
        DocumentConsumer c3 = (f, d) -> callCount[0]++;
        config.setPreImportConsumers(List.of(c1, c2, c3));

        var session = buildSession(config, mock(Fetcher.class));
        var ctx = buildCtx(session, "http://example.com");

        assertThat(new DocumentPreProcessingStage().test(ctx)).isTrue();
        assertThat(callCount[0]).isEqualTo(3);
        verify(session, times(3)).fire(any());
    }

    @Test
    void consumer_receivesCorrectFetcherAndDoc() {
        var config = new CrawlerConfig();
        var fetcher = mock(Fetcher.class);
        var receivedFetchers = new ArrayList<Fetcher>();
        var receivedDocs = new ArrayList<Doc>();
        DocumentConsumer consumer = (f, d) -> {
            receivedFetchers.add(f);
            receivedDocs.add(d);
        };
        config.setPreImportConsumers(List.of(consumer));

        var session = buildSession(config, fetcher);
        var ctx = buildCtx(session, "http://example.com");

        new DocumentPreProcessingStage().test(ctx);

        assertThat(receivedFetchers).containsExactly(fetcher);
        assertThat(receivedDocs).extracting(Doc::getReference)
                .containsExactly("http://example.com");
    }
}
