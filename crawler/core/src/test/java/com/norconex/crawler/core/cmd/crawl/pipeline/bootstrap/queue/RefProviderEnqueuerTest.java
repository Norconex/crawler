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
package com.norconex.crawler.core.cmd.crawl.pipeline.bootstrap.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.doc.pipelines.queue.ReferencesProvider;
import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.session.CrawlerSession;

/**
 * Tests for {@link RefProviderEnqueuer}.
 */
@Timeout(30)
@SuppressWarnings("null")
class RefProviderEnqueuerTest {

    private QueueBootstrapContext buildCtx(
            CrawlerConfig config, List<CrawlerEntry> queued) {
        var session = mock(CrawlerSession.class);
        var crawlContext = mock(CrawlerContext.class);
        when(session.getCrawlContext()).thenReturn(crawlContext);
        when(crawlContext.getCrawlConfig()).thenReturn(config);
        when(crawlContext.createCrawlEntry(anyString()))
                .thenAnswer(inv -> new CrawlerEntry(inv.getArgument(0)));
        return new QueueBootstrapContext(session, queued::add);
    }

    @Test
    void noProviders_returnsZero() {
        var config = new CrawlerConfig();
        var queued = new ArrayList<CrawlerEntry>();
        var ctx = buildCtx(config, queued);

        assertThat(new RefProviderEnqueuer().enqueue(ctx)).isZero();
        assertThat(queued).isEmpty();
    }

    @Test
    void oneProviderWithTwoRefs_queuesBoth() {
        var config = new CrawlerConfig();
        ReferencesProvider provider =
                () -> List.of("ref-1", "ref-2").iterator();
        config.setStartReferencesProviders(List.of(provider));

        var queued = new ArrayList<CrawlerEntry>();
        var ctx = buildCtx(config, queued);

        assertThat(new RefProviderEnqueuer().enqueue(ctx)).isEqualTo(2);
        assertThat(queued).extracting(CrawlerEntry::getReference)
                .containsExactlyInAnyOrder("ref-1", "ref-2");
    }

    @Test
    void nullProviderInList_isSkipped() {
        var config = new CrawlerConfig();
        ReferencesProvider provider =
                () -> List.of("ref-a").iterator();
        var providers = new ArrayList<ReferencesProvider>();
        providers.add(null);
        providers.add(provider);
        config.setStartReferencesProviders(providers);

        var queued = new ArrayList<CrawlerEntry>();
        var ctx = buildCtx(config, queued);

        assertThat(new RefProviderEnqueuer().enqueue(ctx)).isEqualTo(1);
        assertThat(queued).extracting(CrawlerEntry::getReference)
                .containsExactly("ref-a");
    }

    @Test
    void providerReturnsEmptyIterator_queuesNothing() {
        var config = new CrawlerConfig();
        ReferencesProvider provider = Collections::emptyIterator;
        config.setStartReferencesProviders(List.of(provider));

        var queued = new ArrayList<CrawlerEntry>();
        var ctx = buildCtx(config, queued);

        assertThat(new RefProviderEnqueuer().enqueue(ctx)).isZero();
        assertThat(queued).isEmpty();
    }

    @Test
    void twoProviders_queuesFromBoth() {
        var config = new CrawlerConfig();
        ReferencesProvider p1 = () -> List.of("a", "b").iterator();
        ReferencesProvider p2 = () -> List.of("c").iterator();
        config.setStartReferencesProviders(List.of(p1, p2));

        var queued = new ArrayList<CrawlerEntry>();
        var ctx = buildCtx(config, queued);

        assertThat(new RefProviderEnqueuer().enqueue(ctx)).isEqualTo(3);
        assertThat(queued).extracting(CrawlerEntry::getReference)
                .containsExactlyInAnyOrder("a", "b", "c");
    }
}
