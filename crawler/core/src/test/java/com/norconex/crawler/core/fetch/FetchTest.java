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
package com.norconex.crawler.core.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.crawler.core.junit.WithLogLevel;
import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.core.mocks.fetch.MockFetchRequest;
import com.norconex.crawler.core.mocks.fetch.MockFetchResponseImpl;
import com.norconex.crawler.core.mocks.fetch.MockFetcher;
import com.norconex.crawler.core.session.CrawlerSession;

@Timeout(30)
class FetchTest {

    // -----------------------------------------------------------------
    // AggregatedFetchResponse
    // -----------------------------------------------------------------

    @Test
    void testAggregatedFetchResponse_empty() {
        var agg = new AggregatedFetchResponse(List.of());
        assertThat(agg.getProcessingOutcome()).isNull();
        assertThat(agg.getStatusCode()).isZero();
        assertThat(agg.getReasonPhrase()).isNull();
        assertThat(agg.getException()).isNull();
        assertThat(agg.toString()).contains("No fetch responses");
    }

    @Test
    void testAggregatedFetchResponse_singleResponse() {
        var resp = new MockFetchResponseImpl()
                .setProcessingOutcome(ProcessingOutcome.NEW)
                .setStatusCode(200)
                .setReasonPhrase("OK");
        var agg = new AggregatedFetchResponse(List.of(resp));
        assertThat(agg.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(agg.getStatusCode()).isEqualTo(200);
        assertThat(agg.getReasonPhrase()).isEqualTo("OK");
        assertThat(agg.getFetchResponses()).hasSize(1);
        assertThat(agg.toString()).contains("200").contains("OK");
    }

    @Test
    void testAggregatedFetchResponse_multiResponse_returnsLast() {
        var resp1 = new MockFetchResponseImpl()
                .setProcessingOutcome(ProcessingOutcome.ERROR)
                .setStatusCode(500)
                .setReasonPhrase("Server Error");
        var resp2 = new MockFetchResponseImpl()
                .setProcessingOutcome(ProcessingOutcome.NEW)
                .setStatusCode(200)
                .setReasonPhrase("OK");
        var agg = new AggregatedFetchResponse(List.of(resp1, resp2));
        assertThat(agg.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(agg.getStatusCode()).isEqualTo(200);
    }

    // -----------------------------------------------------------------
    // MultiFetcher
    // -----------------------------------------------------------------

    @Test
    void testMultiFetcher_builtWithOneFetcher_hasFetcherInList() {
        var fetcher1 = mock(Fetcher.class);
        var multi = MultiFetcher.builder()
                .fetchers(List.of(fetcher1))
                .responseAggregator(
                        (req, resps) -> resps.get(0))
                .unsuccessfulResponseFactory(
                        (o, m, e) -> new MockFetchResponseImpl()
                                .setProcessingOutcome(
                                        o))
                .build();
        assertThat(multi.getFetchers()).hasSize(1);
    }

    @Test
    void testMultiFetcher_acceptsAlways() {
        var fetcher1 = mock(Fetcher.class);
        when(fetcher1.accept(org.mockito.ArgumentMatchers.any()))
                .thenReturn(false); // not accepted, but MultiFetcher itself accepts

        var multi = MultiFetcher.builder()
                .fetchers(List.of(fetcher1))
                .responseAggregator(
                        (req, resps) -> resps.get(0))
                .unsuccessfulResponseFactory(
                        (o, m, e) -> new MockFetchResponseImpl()
                                .setProcessingOutcome(
                                        o))
                .build();
        var request = new MockFetchRequest("test-ref");
        assertThat(multi.accept(request)).isTrue();
    }

    @Test
    void testMultiFetcher_returnsUnsupportedWhenNoFetcherAccepts()
            throws Exception {
        var fetcher1 = mock(Fetcher.class);
        when(fetcher1.accept(org.mockito.ArgumentMatchers.any()))
                .thenReturn(false);

        var multi = MultiFetcher.builder()
                .fetchers(List.of(fetcher1))
                .responseAggregator(
                        (req, resps) -> new AggregatedFetchResponse(
                                resps))
                .unsuccessfulResponseFactory(
                        (o, m, e) -> new MockFetchResponseImpl()
                                .setProcessingOutcome(
                                        o))
                .build();

        var request = new MockFetchRequest("test-ref");
        var response = multi.fetch(request);
        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.UNSUPPORTED);
    }

    @Test
    void testMultiFetcher_successOnFirstFetcher() throws Exception {
        var mockResponse = new MockFetchResponseImpl()
                .setProcessingOutcome(ProcessingOutcome.NEW)
                .setStatusCode(200)
                .setReasonPhrase("OK");
        var fetcher1 = mock(Fetcher.class);
        when(fetcher1.accept(org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(fetcher1.fetch(org.mockito.ArgumentMatchers.any()))
                .thenReturn(mockResponse);

        var multi = MultiFetcher.builder()
                .fetchers(List.of(fetcher1))
                .responseAggregator(
                        (req, resps) -> new AggregatedFetchResponse(
                                resps))
                .unsuccessfulResponseFactory(
                        (o, m, e) -> new MockFetchResponseImpl()
                                .setProcessingOutcome(
                                        o))
                .build();

        var request = new MockFetchRequest("test-ref");
        var response = multi.fetch(request);
        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
    }

    @Test
    void testMultiFetcher_handlesNullFetchResponse() throws Exception {
        var fetcher1 = mock(Fetcher.class);
        when(fetcher1.accept(org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(fetcher1.fetch(org.mockito.ArgumentMatchers.any()))
                .thenReturn(null); // fetcher returns null

        var multi = MultiFetcher.builder()
                .fetchers(List.of(fetcher1))
                .responseAggregator(
                        (req, resps) -> new AggregatedFetchResponse(
                                resps))
                .unsuccessfulResponseFactory(
                        (o, m, e) -> new MockFetchResponseImpl()
                                .setProcessingOutcome(
                                        o))
                .build();

        var request = new MockFetchRequest("test-ref");
        // null response should be treated as unsupported
        var response = multi.fetch(request);
        assertThat(response).isNotNull();
    }

    @Test
    @WithLogLevel(value = "OFF", classes = MultiFetcher.class)
    void testMultiFetcher_handlesExceptionFromFetcher() throws Exception {
        var fetcher1 = mock(Fetcher.class);
        when(fetcher1.accept(org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(fetcher1.fetch(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException(
                        "Fetch failure"));

        var multi = MultiFetcher.builder()
                .fetchers(List.of(fetcher1))
                .responseAggregator(
                        (req, resps) -> new AggregatedFetchResponse(
                                resps))
                .unsuccessfulResponseFactory(
                        (o, m, e) -> new MockFetchResponseImpl()
                                .setProcessingOutcome(
                                        o))
                .build();

        var request = new MockFetchRequest("test-ref");
        var response = multi.fetch(request);
        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.ERROR);
    }

    @Test
    void testMultiFetcher_getFetchers() {
        var fetcher1 = mock(Fetcher.class);
        when(fetcher1.accept(org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        var multi = MultiFetcher.builder()
                .fetchers(List.of(fetcher1))
                .responseAggregator(
                        (req, resps) -> resps.get(0))
                .unsuccessfulResponseFactory(
                        (o, m, e) -> new MockFetchResponseImpl()
                                .setProcessingOutcome(
                                        o))
                .build();
        assertThat(multi.getFetchers()).hasSize(1);
        assertThat(multi.getMaxRetries()).isZero();
    }

    @Test
    void testMultiFetcher_withRetries_retriesUntilSuccess()
            throws Exception {
        var badResponse = new MockFetchResponseImpl()
                .setProcessingOutcome(
                        ProcessingOutcome.BAD_STATUS);
        var goodResponse = new MockFetchResponseImpl()
                .setProcessingOutcome(ProcessingOutcome.NEW);
        var fetcher1 = mock(Fetcher.class);
        when(fetcher1.accept(org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(fetcher1.fetch(org.mockito.ArgumentMatchers.any()))
                .thenReturn(badResponse)
                .thenReturn(badResponse)
                .thenReturn(goodResponse);

        var multi = MultiFetcher.builder()
                .fetchers(List.of(fetcher1))
                .responseAggregator(
                        (req, resps) -> resps.get(resps
                                .size() - 1))
                .unsuccessfulResponseFactory(
                        (o, m, e) -> new MockFetchResponseImpl()
                                .setProcessingOutcome(
                                        o))
                .maxRetries(3)
                .build();

        var response = multi.fetch(new MockFetchRequest("retry-ref"));
        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
    }

    @Test
    void testMultiFetcher_withRetryDelay_allRetriesFail_returnsBadStatus()
            throws Exception {
        var badResponse = new MockFetchResponseImpl()
                .setProcessingOutcome(
                        ProcessingOutcome.BAD_STATUS);
        var fetcher1 = mock(Fetcher.class);
        when(fetcher1.accept(org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(fetcher1.fetch(org.mockito.ArgumentMatchers.any()))
                .thenReturn(badResponse);

        var multi = MultiFetcher.builder()
                .fetchers(List.of(fetcher1))
                .responseAggregator(
                        (req, resps) -> resps.get(resps
                                .size() - 1))
                .unsuccessfulResponseFactory(
                        (o, m, e) -> new MockFetchResponseImpl()
                                .setProcessingOutcome(
                                        o))
                .maxRetries(1)
                .retryDelay(java.time.Duration.ofMillis(1))
                .build();

        var response = multi
                .fetch(new MockFetchRequest("delay-retry-ref"));
        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.BAD_STATUS);
    }

    // -----------------------------------------------------------------
    // AbstractFetcher (via MockFetcher)
    // -----------------------------------------------------------------

    @Test
    void testAbstractFetcher_accept_noFilters_returnsTrue() {
        var fetcher = new MockFetcher();
        assertThat(fetcher
                .accept(new MockFetchRequest("http://any.com")))
                        .isTrue();
    }

    @Test
    void testAbstractFetcher_accept_deniedRequest_returnsFalse() {
        var fetcher = new MockFetcher();
        fetcher.getConfiguration().setDenyRequest(Boolean.TRUE);
        assertThat(fetcher
                .accept(new MockFetchRequest("http://any.com")))
                        .isFalse();
    }

    @Test
    void testAbstractFetcher_accept_notDeniedRequest_returnsTrue() {
        var fetcher = new MockFetcher();
        fetcher.getConfiguration().setDenyRequest(Boolean.FALSE);
        assertThat(fetcher
                .accept(new MockFetchRequest("http://any.com")))
                        .isTrue();
    }

    @Test
    void testAbstractFetcher_onCrawlBeginAndEndEvents_doNotThrow() {
        var fetcher = new MockFetcher();
        var session = mock(CrawlerSession.class);
        var beginEvent = CrawlerEvent.builder()
                .name(CrawlerEvent.CRAWLER_CRAWL_BEGIN)
                .source(session)
                .build();
        var endEvent = CrawlerEvent.builder()
                .name(CrawlerEvent.CRAWLER_CRAWL_END)
                .source(session)
                .build();
        assertThatNoException().isThrownBy(() -> {
            fetcher.accept(beginEvent);
            fetcher.accept(endEvent);
        });
    }

    @Test
    void testFetchUtil_metadataRequired_shouldNotContinue() {
        var ctx = mock(CrawlerContext.class);
        var cfg = new CrawlerConfig();
        cfg.setMetadataFetchSupport(FetchDirectiveSupport.REQUIRED);
        when(ctx.getCrawlConfig()).thenReturn(cfg);

        var result = FetchUtil.shouldContinueOnBadStatus(
                ctx, ProcessingOutcome.ERROR,
                FetchDirective.METADATA);
        assertThat(result).isFalse();
    }

    @Test
    void testFetchUtil_metadataOptionalWithDocEnabled_shouldContinue() {
        var ctx = mock(CrawlerContext.class);
        var cfg = new CrawlerConfig();
        cfg.setMetadataFetchSupport(FetchDirectiveSupport.OPTIONAL);
        cfg.setDocumentFetchSupport(FetchDirectiveSupport.REQUIRED);
        when(ctx.getCrawlConfig()).thenReturn(cfg);

        var result = FetchUtil.shouldContinueOnBadStatus(
                ctx, ProcessingOutcome.ERROR,
                FetchDirective.METADATA);
        assertThat(result).isTrue();
    }

    @Test
    void testFetchUtil_metadataOptionalWithDocDisabled_shouldNotContinue() {
        var ctx = mock(CrawlerContext.class);
        var cfg = new CrawlerConfig();
        cfg.setMetadataFetchSupport(FetchDirectiveSupport.OPTIONAL);
        cfg.setDocumentFetchSupport(FetchDirectiveSupport.DISABLED);
        when(ctx.getCrawlConfig()).thenReturn(cfg);

        var result = FetchUtil.shouldContinueOnBadStatus(
                ctx, ProcessingOutcome.ERROR,
                FetchDirective.METADATA);
        assertThat(result).isFalse();
    }

    @Test
    void testFetchUtil_documentRequired_shouldNotContinue() {
        var ctx = mock(CrawlerContext.class);
        var cfg = new CrawlerConfig();
        cfg.setDocumentFetchSupport(FetchDirectiveSupport.REQUIRED);
        when(ctx.getCrawlConfig()).thenReturn(cfg);

        var result = FetchUtil.shouldContinueOnBadStatus(
                ctx, ProcessingOutcome.ERROR,
                FetchDirective.DOCUMENT);
        assertThat(result).isFalse();
    }

    @Test
    void testFetchUtil_documentOptionalWithMetaEnabledAndGoodStatus_shouldContinue() {
        var ctx = mock(CrawlerContext.class);
        var cfg = new CrawlerConfig();
        cfg.setDocumentFetchSupport(FetchDirectiveSupport.OPTIONAL);
        cfg.setMetadataFetchSupport(FetchDirectiveSupport.REQUIRED);
        when(ctx.getCrawlConfig()).thenReturn(cfg);

        var result = FetchUtil.shouldContinueOnBadStatus(
                ctx, ProcessingOutcome.NEW,
                FetchDirective.DOCUMENT);
        assertThat(result).isTrue();
    }

    @Test
    void testFetchUtil_documentOptionalWithMetaEnabledAndBadStatus_shouldNotContinue() {
        var ctx = mock(CrawlerContext.class);
        var cfg = new CrawlerConfig();
        cfg.setDocumentFetchSupport(FetchDirectiveSupport.OPTIONAL);
        cfg.setMetadataFetchSupport(FetchDirectiveSupport.REQUIRED);
        when(ctx.getCrawlConfig()).thenReturn(cfg);

        var result = FetchUtil.shouldContinueOnBadStatus(
                ctx, ProcessingOutcome.ERROR,
                FetchDirective.DOCUMENT);
        assertThat(result).isFalse();
    }

    @Test
    void testFetchUtil_disabledDirective_shouldNotContinue() {
        var ctx = mock(CrawlerContext.class);
        var cfg = new CrawlerConfig();
        // Set both directives to disabled
        cfg.setMetadataFetchSupport(FetchDirectiveSupport.DISABLED);
        cfg.setDocumentFetchSupport(FetchDirectiveSupport.DISABLED);
        when(ctx.getCrawlConfig()).thenReturn(cfg);

        // A disabled directive means the fetch should not have happened
        var resultMeta = FetchUtil.shouldContinueOnBadStatus(
                ctx, ProcessingOutcome.ERROR,
                FetchDirective.METADATA);
        var resultDoc = FetchUtil.shouldContinueOnBadStatus(
                ctx, ProcessingOutcome.ERROR,
                FetchDirective.DOCUMENT);
        assertThat(resultMeta).isFalse();
        assertThat(resultDoc).isFalse();
    }
}
