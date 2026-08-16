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

import com.norconex.commons.lang.config.Configurable;
import com.norconex.commons.lang.event.Event;
import com.norconex.commons.lang.event.EventListener;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.crawler.core.cluster.CacheSet;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.importer.doc.Doc;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Provides the ability to send deletion requests to your configured
 * committer(s) whenever a reference is rejected, regardless whether it was
 * encountered in a previous crawling session or not.
 * </p>
 *
 * <h2>Supported events</h2>
 * <p>
 * By default this listener will send deletion requests for all references
 * associated with a {@link CrawlerEvent} name starting with
 * <code>REJECTED_</code>. To avoid performance issues when dealing with
 * too many deletion requests, it is recommended you can change this behavior
 * to match exactly the events you are interested in with
 * {@link DeleteRejectedEventListenerConfig#setEventMatcher(TextMatcher)}.
 * Keep limiting events to "rejected" ones to avoid unexpected results.
 * </p>
 *
 * <h2>Deletion requests sent once</h2>
 * <p>
 * This class tries to handles each reference for "rejected" events only once.
 * To do so it will queue all such references and wait until normal
 * crawler completion to send them. Waiting for completion also gives this
 * class a chance to listen for deletion requests sent to your committer as
 * part of the crawler regular execution (typically on subsequent crawls).
 * This helps ensure you do not get duplicate deletion requests for the same
 * reference.
 * </p>
 *
 * <h2>Only references</h2>
 * <p>
 * Since several rejection events are triggered before document are processed,
 * we can't assume there is any metadata attached with rejected
 * references. Be aware this can cause issues if you are using rules in your
 * committer (e.g., to route requests) based on metadata.
 * </p>
 */
@EqualsAndHashCode
@ToString
@Slf4j
public class DeleteRejectedEventListener implements
        EventListener<Event>, Configurable<DeleteRejectedEventListenerConfig> {

    public static final String DELETED_REFS_CACHE_NAME = "rejected-refs";

    @Getter
    private final DeleteRejectedEventListenerConfig configuration =
            new DeleteRejectedEventListenerConfig();

    // key=reference; value=whether deletion request was already sent
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private CacheSet refCache;
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private boolean doneCrawling;

    @Override
    public void accept(Event event) {
        if (!(event instanceof CrawlerEvent crawlerEvent) || doneCrawling) {
            return;
        }

        if (event.is(CrawlerEvent.CRAWLER_CRAWL_BEGIN)) {
            init(crawlerEvent.getCrawlSession());
        } else if (CrawlerEvent.CRAWLER_CRAWL_END.equals(event.getName())) {
            doneCrawling = true;
            commitDeletions(crawlerEvent.getCrawlSession());
        } else {
            storeRejection(crawlerEvent);
        }
    }

    private void init(CrawlerSession crawlSession) {
        // Delete any previously created store. We do it here instead
        // of on completion in case users want to keep a record between
        // two crawl executions.
        refCache = crawlSession.getCluster().getCacheManager().getCacheSet(
                DELETED_REFS_CACHE_NAME);
        crawlSession.oncePerSession("delete-event-deletions-cache", () -> {
            LOG.info("Clearing any previous deleted "
                    + "references cache.");
            refCache.clear();
        });
    }

    private void storeRejection(CrawlerEvent event) {
        // does it match?
        if (!configuration.getEventMatcher().matches(event.getName())) {
            LOG.trace("Event not matching event matcher: {}", event.getName());
            return;
        }

        // does it have a document reference?
        var docInfo = event.getCrawlEntry();
        if (docInfo == null) {
            LOG.warn("Listening for reference rejections on a crawler event "
                    + "that has no reference: {}",
                    event.getName());
            return;
        }
        refCache.add(docInfo.getReference());
    }

    private void commitDeletions(CrawlerSession crawlSession) {
        crawlSession.oncePerSession("commit-event-deletions", () -> {
            if (!refCache.isEmpty()) {

                if (LOG.isInfoEnabled()) {
                    LOG.info("Committing {} rejected references "
                            + "for deletion...", refCache.size());
                }
                refCache.forEach(
                        ref -> crawlSession.getCrawlContext()
                                .getCommitterService()
                                .delete(new Doc(ref)));
                LOG.info("Done committing rejected references.");
            }
        });
    }
}
