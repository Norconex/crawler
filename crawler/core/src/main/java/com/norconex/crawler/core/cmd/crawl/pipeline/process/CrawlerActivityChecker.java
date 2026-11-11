/* Copyright 2024-2026 Norconex Inc.
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

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import com.norconex.commons.lang.Sleeper;
import com.norconex.commons.lang.time.DurationFormatter;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.core.session.CrawlerState;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Uses a few different criteria to find out if the crawler should still be
 * considered active. When applicable, will wait until that information
 * can be established.
 */
@Slf4j
//TODO still needed? Done by cluster?
class CrawlerActivityChecker {
    private final CrawlerSession session;
    @Getter
    private final boolean deleting;
    private final Instant expireAt;

    private boolean isResolving;
    private boolean isPresumedActive = true;
    private final AtomicBoolean canContinue = new AtomicBoolean(true);
    //    private boolean maxDocsReached;

    public CrawlerActivityChecker(CrawlerSession session, boolean deleting) {
        this.session = session;
        this.deleting = deleting;
        var maxDuration = session
                .getCrawlContext().getCrawlConfig().getMaxCrawlDuration();
        expireAt = (maxDuration == null || maxDuration.isNegative()
                || maxDuration.isZero())
                        ? null
                        : Instant.now().plus(maxDuration);
    }

    //TODO can we do without synchronize?
    synchronized boolean isActive() {
        if (!isPresumedActive) {
            LOG.trace("isActive(): presumed inactive; returning false.");
            return false;
        }
        if (isResolving) {
            LOG.trace("isActive(): another thread is already resolving; "
                    + "returning presumed active (true).");
            return true;
        }
        isResolving = true;
        try {
            var active = doIsActive();
            LOG.trace("isActive(): resolved active={} (prevPresumed={}).",
                    active, isPresumedActive);
            isPresumedActive = active;
        } finally {
            isResolving = false;
        }
        return isPresumedActive;
    }

    private boolean doIsActive() {
        var queuedEntryCount = queuedEntryCount();
        if (LOG.isTraceEnabled()) {
            LOG.trace("""
                doIsActive(): canContinue={} queueInitialized={} \
                queueEmpty={} deleting={} expireAt={} \
                nowMs={}.""",
                    canContinue.get(),
                    session.isStartRefsQueueingComplete(),
                    queuedEntryCount == 0,
                    deleting,
                    expireAt,
                    Instant.now());
        }
        if (!canContinue()) {
            LOG.info("doIsActive(): canContinue is false; crawler "
                    + "considered inactive.");
            return false;
        }
        if (isQueueInitializedAndEmpty()) {
            var stillEmpty = isQueueStillEmptyAfterIdleTimeout();
            LOG.trace("doIsActive(): queue initialized and empty; "
                    + "stillEmptyAfterIdleTimeout={}.", stillEmpty);
            return !stillEmpty;
        }
        LOG.trace("doIsActive(): queue is not both initialized and "
                + "empty; treating crawler as active.");
        return true;
    }

    boolean canContinue() {
        if (!canContinue.get()) {
            LOG.trace("canContinue(): global flag already false; "
                    + "returning false.");
            return false;
        }

        var can = doCanContinue();
        if (LOG.isTraceEnabled()) {
            LOG.trace("canContinue(): evaluated can={} (was={}).", can,
                    canContinue.get());
        }
        if (!can) {
            LOG.info("Stopping crawl execution (i.e., pause). Will "
                    + "resume on next start.");
            session.updateCrawlState(CrawlerState.STOPPED);
        }

        canContinue.set(can);
        return can;
    }

    boolean doCanContinue() {
        // If deleting we don't care about checking if max is reached,
        // we proceed.
        if (deleting) {
            LOG.trace("doCanContinue(): deleting=true; bypassing "
                    + "max-docs/duration checks.");
            return true;
        }

        if (isCrawlExpired()) {
            LOG.info("Max crawl duration reached.");
            return false;
        }

        if (session.getCrawlContext().getCrawlEntryLedger()
                .isMaxDocsProcessedReached()) {
            LOG.info("Max number of documents reached.");
            return false;
        }
        return true;
    }

    private boolean isCrawlExpired() {
        var expired = expireAt != null && Instant.now().isAfter(expireAt);
        if (expired && LOG.isDebugEnabled()) {
            LOG.debug("isCrawlExpired(): expireAt={} now={} -> true.",
                    expireAt, Instant.now());
        }
        return expired;
    }

    private boolean isQueueInitializedAndEmpty() {
        var queueEmpty = isQueuedEntryEmpty();
        if (LOG.isTraceEnabled()) {
            LOG.trace("isQueueInitializedAndEmpty(): start queueEmpty={} "
                    + "startRefsQueuedComplete={}",
                    queueEmpty,
                    session.isStartRefsQueueingComplete());
        }
        if (queueEmpty) {
            var queueInitialized = session.isStartRefsQueueingComplete();
            if (!queueInitialized) {
                LOG.info("""
                    References are still being queued. \
                    Waiting for new references or initial queuing \
                    to be over...""");
                do {
                    Sleeper.sleepMillis(200);
                    queueEmpty = isQueuedEntryEmpty();
                    queueInitialized =
                            session.isStartRefsQueueingComplete();
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("""
                            isQueueInitializedAndEmpty(): \
                            poll loop queueEmpty={} \
                            queueInitialized={}.""",
                                queueEmpty, queueInitialized);
                    }
                    // Queueing can take long (e.g., large sitemaps) and
                    // leave the queue empty in the meantime: keep waiting
                    // for as long as the node doing it is alive. Only when
                    // it left the cluster (e.g., crashed) is nobody going to
                    // finish it in this run. It is then resumed with the
                    // crawl, since it was not marked as fully done.
                    if (!queueInitialized && queueEmpty
                            && !session.isStartRefsQueueingNodeAlive()) {
                        LOG.warn("The node queueing start references left "
                                + "the cluster before it was done. Not "
                                + "waiting for it: queueing resumes with "
                                + "the next run.");
                        queueInitialized = true;
                    }
                } while (queueEmpty && !queueInitialized);
            }
        }
        if (queueEmpty) {
            LOG.debug("Reference queue is empty.");
        } else if (LOG.isTraceEnabled()) {
            LOG.trace("isQueueInitializedAndEmpty(): queue not empty; "
                    + "returning false.");
        }
        return queueEmpty;
    }

    private boolean isQueueStillEmptyAfterIdleTimeout() {
        var duration =
                session.getCrawlContext().getCrawlConfig().getIdleTimeout();
        if (duration == null || duration.isZero()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("isQueueStillEmptyAfterIdleTimeout(): no "
                        + "idleTimeout configured; returning true.");
            }
            return true;
        }

        LOG.info("Waiting up to {} for references to be added to the queue.",
                idleTimeoutAsText());
        var timeoutMs = duration.toMillis();
        var start = Instant.now();
        while (Duration.between(start, Instant.now()).toMillis() < timeoutMs) {
            Sleeper.sleepMillis(200);
            var empty = isQueuedEntryEmpty();
            if (LOG.isTraceEnabled()) {
                LOG.trace("isQueueStillEmptyAfterIdleTimeout(): polled "
                        + "queueEmpty={} after {} (timeout={} ms).",
                        empty,
                        Duration.between(start, Instant.now()),
                        timeoutMs);
            }
            if (!empty) {
                return false;
            }
        }
        LOG.info("This crawler node has been idle for more than {}.",
                idleTimeoutAsText());
        return true;
    }

    private boolean isQueuedEntryEmpty() {
        return queuedEntryCount() == 0;
    }

    private long queuedEntryCount() {
        return session.getCrawlContext()
                .getCrawlEntryLedger()
                .getQueuedEntryCount();
    }

    private String idleTimeoutAsText() {
        return DurationFormatter.FULL.format(
                session.getCrawlContext().getCrawlConfig().getIdleTimeout());
    }

}
