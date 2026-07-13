/* Copyright 2021-2026 Norconex Inc.
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
package com.norconex.crawler.core.event.listeners;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.commons.lang.event.Event;
import com.norconex.commons.lang.event.EventListener;
import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.event.listeners.StopCrawlerOnMaxEventListenerConfig.OnMultiple;
import com.norconex.crawler.core.session.CrawlerSession;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Alternative to {@link CrawlerConfig#setMaxDocuments(int)} for issuing a
 * crawler stop request upon reaching specific event counts. The event counts
 * are only kept for a crawling session. They are reset to zero upon restarting
 * the crawler.
 * </p>
 * <p>
 * Not specifying any maximum or events has no effect.
 * </p>
 *
 * <p>
 * <b>Note</b> that when multiple threads/nodes are generating events, it is
 * very well possible to get more events fired after the stop request has been
 * issued. In other words, running tasks usually finish cleanly if they can.
 * </p>
 *
 * <h2>Difference with "maxDocuments"</h2>
 * <p>
 * The "maxDocuments" option deals with "processed" documents. Those are
 * documents that were initially queued for crawling and crawling was attempted
 * on them, whether that exercise what successful or not. That is,
 * "maxDocuments" will not count documents that were sent to your committer
 * for additions or deletions, but also documents that were rejected
 * by your Importer configuration, produced errors, etc.
 * This class gives you more control over what should trigger a crawler to stop.
 * </p>
 * <p>
 * Note that for this class to take effect, make sure that "maxDocuments" has
 * a high enough number or is set <code>-1</code> (unlimited).
 * </p>
 *
 * <h2>Combining events</h2>
 * <p>
 * If your event matcher matches more than one event, you can decide what
 * should be the expected behavior. Options are:
 * </p>
 * <ul>
 * <li>
 * <b>any</b>: Stop the crawler when any of the matching event count
 * reaches the specified maximum.
 * </li>
 * <li>
 * <b>all</b>: Stop the crawler when all of the matching event counts
 * have reached the maximum.
 * </li>
 * <li>
 * <b>sum</b>: Stop the crawler when the sum of all matching event counts
 * have reached the maximum.
 * </li>
 * </ul>
 */
@Slf4j
@EqualsAndHashCode
@ToString
public class StopCrawlerOnMaxEventListener implements
        EventListener<Event>,
        Configurable<StopCrawlerOnMaxEventListenerConfig> {

    @Getter
    private final StopCrawlerOnMaxEventListenerConfig configuration =
            new StopCrawlerOnMaxEventListenerConfig();

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Map<String, AtomicLong> eventCounts = new ConcurrentHashMap<>();
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private CrawlerSession crawlSession;

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private AtomicBoolean stopRequested = new AtomicBoolean();

    @Override
    public void accept(Event event) {
        if (event instanceof CrawlerEvent crawlerEvent
                && event.is(CrawlerEvent.CRAWLER_CRAWL_BEGIN)) {
            eventCounts.clear();
            crawlSession = crawlerEvent.getCrawlSession();
        }

        if (!configuration.getEventMatcher().matches(event.getName())) {
            return;
        }

        eventCounts.computeIfAbsent(
                event.getName(), k -> new AtomicLong()).incrementAndGet();

        if (isMaxReached()) {
            doStop();
        }
    }

    private synchronized void doStop() {
        if (!stopRequested.get()) {
            stopRequested.set(true);
            LOG.info("Maximum number of {} events reached for crawler: {}. "
                    + "Issuing a stop request.",
                    configuration.getMaximum(), crawlSession.getCrawlerId());
            crawlSession.getCluster().stop();
        }
    }

    private boolean isMaxReached() {
        var maximum = configuration.getMaximum();
        if (OnMultiple.ALL == configuration.getOnMultiple()) {
            return eventCounts.values().stream()
                    .allMatch(v -> v.get() >= maximum);
        }
        if (OnMultiple.SUM == configuration.getOnMultiple()) {
            return eventCounts.values().stream().collect(
                    Collectors.summingLong(AtomicLong::get)) >= maximum;
        }
        return eventCounts.values().stream()
                .anyMatch(v -> v.get() >= maximum);
    }
}
