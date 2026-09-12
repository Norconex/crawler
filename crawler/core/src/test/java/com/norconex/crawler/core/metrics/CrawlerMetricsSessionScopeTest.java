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
package com.norconex.crawler.core.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;

import com.norconex.commons.lang.event.EventManager;
import com.norconex.crawler.core.cluster.Cluster;
import com.norconex.crawler.core.cluster.support.InMemoryCacheManager;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.ledger.CrawlerEntryLedger;
import com.norconex.crawler.core.session.CrawlerMode;
import com.norconex.crawler.core.session.CrawlerResumeState;
import com.norconex.crawler.core.session.CrawlerRunInfo;
import com.norconex.crawler.core.session.CrawlerRunInfoResolver;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.core.session.CrawlerState;
import com.norconex.crawler.core.util.SerialUtil;

/**
 * How long an event count is supposed to live.
 * <p>
 * The other metrics tests mock the cache and exercise a single
 * {@code init()}, so they never ask what happens to a count when one crawl
 * ends and another begins. That question has two different answers, depending
 * on which boundary is crossed:
 * </p>
 * <ul>
 *   <li>A <b>resumed</b> crawl is one session carrying on after being
 *       stopped, so its counts must survive: the work was not done twice, and
 *       the execution summary reports them as "incl. resumed".</li>
 *   <li>A <b>new session</b> is a different crawl, so it must start from
 *       zero. Otherwise every recrawl of a source reports its own numbers plus
 *       those of every crawl before it, for as long as the work directory
 *       lives.</li>
 * </ul>
 * <p>
 * The boundary is decided by {@link CrawlerRunInfoResolver}, so that is what
 * these tests drive &mdash; asserting on counts through a real cache manager
 * rather than on the resolver's return value. One
 * {@link InMemoryCacheManager} stands in for the crawl store, whose maps are
 * shared by name and which outlives any single session, exactly as MVStore
 * behaves for a standalone crawl reusing a work directory.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@Timeout(30)
class CrawlerMetricsSessionScopeTest {

    @Mock(strictness = Strictness.LENIENT)
    private CrawlerSession session;
    @Mock(strictness = Strictness.LENIENT)
    private Cluster cluster;

    /** The crawl store: one per work directory, outliving every session. */
    private InMemoryCacheManager crawlStore;

    @BeforeEach
    void setUp() {
        crawlStore = new InMemoryCacheManager();
        var ctx = mock(CrawlerContext.class);
        lenient().when(ctx.getCrawlEntryLedger())
                .thenReturn(mock(CrawlerEntryLedger.class));
        lenient().when(ctx.getEventManager()).thenReturn(new EventManager());

        lenient().when(session.getCrawlerId()).thenReturn("crawler-1");
        lenient().when(session.getCluster()).thenReturn(cluster);
        lenient().when(session.getCrawlContext()).thenReturn(ctx);
        lenient().when(cluster.getCacheManager()).thenReturn(crawlStore);
        lenient().when(session.loadState()).thenReturn(null);
    }

    /** Event counts a previous crawl left behind in the store. */
    private void countsFromAPreviousCrawl() {
        // Incremented through the real path, so the values land in the store
        // the same way a crawl would put them there.
        var metrics = new CrawlerMetricsImpl();
        metrics.init(session);
        metrics.incrementCounter("COMMITTER_UPSERT_END", 400L);
        metrics.incrementCounter("DOCUMENT_QUEUED", 403L);
    }

    /** What a later process would find in the store. */
    private Map<String, Long> countsInStore() {
        var metrics = new CrawlerMetricsImpl();
        metrics.init(session);
        return metrics.getEventCounts();
    }

    /**
     * Stores run info for a prior run and primes the state the resolver reads
     * to decide new-versus-resumed.
     */
    private void priorSession(CrawlerState priorState) {
        crawlStore.getCrawlSessionCache().put(
                CrawlerRunInfoResolver.CRAWL_RUN_INFO_KEY,
                SerialUtil.toJsonString(CrawlerRunInfo.builder()
                        .crawlerId("crawler-1")
                        .crawlSessionId("cs-prior")
                        .crawlRunId("cr-prior")
                        .crawlMode(CrawlerMode.FULL)
                        .crawlResumeState(CrawlerResumeState.NEW)
                        .build()));
        when(session.loadState()).thenReturn(
                new CrawlerSession.State()
                        .setCrawlState(priorState)
                        .setLastUpdated(System.currentTimeMillis()));
    }

    // -----------------------------------------------------------------
    // Resuming: one crawl carrying on
    // -----------------------------------------------------------------

    /**
     * Every state that means "this crawl did not finish" resumes the same
     * session, so the counts of the earlier attempt are still this crawl's.
     */
    @ParameterizedTest
    @EnumSource(
        value = CrawlerState.class,
        names = { "RUNNING", "STOPPED", "FAILED" }
    )
    void resumedSessionKeepsItsEarlierCounts(CrawlerState priorState) {
        countsFromAPreviousCrawl();
        priorSession(priorState);

        var info = CrawlerRunInfoResolver.resolve(session);

        assertThat(info.getCrawlResumeState())
                .isEqualTo(CrawlerResumeState.RESUMED);
        assertThat(countsInStore())
                .as("a resumed crawl continues its session's totals")
                .containsEntry("COMMITTER_UPSERT_END", 400L)
                .containsEntry("DOCUMENT_QUEUED", 403L);
    }

    // -----------------------------------------------------------------
    // A new session: a different crawl
    // -----------------------------------------------------------------

    @Test
    void newSessionAfterACompletedOneStartsFromZero() {
        countsFromAPreviousCrawl();
        priorSession(CrawlerState.COMPLETED);

        var info = CrawlerRunInfoResolver.resolve(session);

        // A completed crawl followed by another is a new session with a new
        // id -- and, before this was fixed, the one path that minted a fresh
        // session while clearing nothing.
        assertThat(info.getCrawlResumeState())
                .isEqualTo(CrawlerResumeState.NEW);
        assertThat(info.getCrawlSessionId()).isNotEqualTo("cs-prior");
        assertThat(countsInStore())
                .as("a new crawl session must not inherit another's counts")
                .isEmpty();
    }

    @Test
    void firstEverCrawlStartsFromZero() {
        // Nothing persisted, but the store somehow holds counts: a work
        // directory whose session cache was wiped while its other maps were
        // not. The numbers still are not this crawl's.
        countsFromAPreviousCrawl();

        var info = CrawlerRunInfoResolver.resolve(session);

        assertThat(info.getCrawlResumeState())
                .isEqualTo(CrawlerResumeState.NEW);
        assertThat(countsInStore()).isEmpty();
    }

    @Test
    void aNewSessionCountsOnlyItsOwnWork() {
        countsFromAPreviousCrawl();
        priorSession(CrawlerState.COMPLETED);

        CrawlerRunInfoResolver.resolve(session);

        // The new session then does a little work of its own.
        var metrics = new CrawlerMetricsImpl();
        metrics.init(session);
        metrics.incrementCounter("COMMITTER_UPSERT_END", 5L);

        assertThat(metrics.getEventCounts())
                .as("this crawl committed 5 documents, not 405")
                .containsEntry("COMMITTER_UPSERT_END", 5L);
    }
}
