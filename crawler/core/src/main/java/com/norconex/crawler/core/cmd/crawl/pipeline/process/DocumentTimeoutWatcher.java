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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.norconex.commons.lang.bean.BeanUtil;
import com.norconex.commons.lang.time.DurationFormatter;
import com.norconex.crawler.core.CrawlerException;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.core.ledger.ProcessingStatus;
import com.norconex.crawler.core.session.CrawlerSession;

import lombok.extern.slf4j.Slf4j;

/**
 * Gives up on documents a node's crawl threads take too long to process.
 * <p>
 * A document processed for longer than the document timeout is recorded as
 * an error, nothing more of it is sent to committers, and its thread is
 * interrupted. If the thread still has not let go of it after a grace
 * period, a new thread takes its place. Should as many threads as the node
 * crawls with be stuck that way, the crawl is stopped (to be resumed on the
 * next start): something is keeping documents from ever completing.
 * </p>
 */
@Slf4j
final class DocumentTimeoutWatcher implements AutoCloseable {

    /**
     * Longest an interrupted thread has to let go of its document: a minute,
     * or the document timeout if shorter.
     */
    static final long MAX_ABANDON_GRACE_MS = 60_000;

    private final CrawlerSession session;
    private final List<CrawlSlot> slots;
    private final Consumer<CrawlSlot> slotRestarter;
    private final long timeoutMs;
    private final long abandonGraceMs;
    private final ScheduledExecutorService scheduler;
    private final List<Thread> abandoned = new ArrayList<>();
    private boolean gaveUp;

    /**
     * Starts watching, unless the timeout is {@code null}, zero or negative.
     * @param session crawl session
     * @param slots the node's crawl slots
     * @param slotRestarter starts a new thread for a slot
     * @param timeout document timeout
     */
    DocumentTimeoutWatcher(CrawlerSession session, List<CrawlSlot> slots,
            Consumer<CrawlSlot> slotRestarter, Duration timeout) {
        this.session = session;
        this.slots = slots;
        this.slotRestarter = slotRestarter;
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeoutMs = 0;
            abandonGraceMs = 0;
            scheduler = null;
            return;
        }
        timeoutMs = timeout.toMillis();
        abandonGraceMs = Math.min(MAX_ABANDON_GRACE_MS, timeoutMs);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, session.getCrawlerId() + "-doc-timeout");
            t.setDaemon(true);
            return t;
        });
        var intervalMs = Math.clamp(timeoutMs / 10, 100L, 1_000L);
        scheduler.scheduleWithFixedDelay(
                this::check, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    void check() {
        for (CrawlSlot slot : slots) {
            try {
                checkSlot(slot);
            } catch (RuntimeException e) {
                LOG.error("Could not check document processing time on "
                        + "crawl thread #{}.", slot.getIndex(), e);
            }
        }
    }

    private void checkSlot(CrawlSlot slot) {
        var doc = slot.getCurrent();
        if (doc == null) {
            return;
        }
        var elapsed = System.currentTimeMillis() - doc.getStartedAt();
        if (elapsed < timeoutMs) {
            return;
        }
        var action = doc.timeOut();
        if (action != DocumentInProcess.TimeoutAction.NONE) {
            LOG.warn("Giving up on {}: its processing took longer than the "
                    + "document timeout ({}).", doc.getReference(),
                    timeoutAsText());
            if (action == DocumentInProcess.TimeoutAction.RECORD_ERROR) {
                recordTimeout(doc);
            }
            slot.interruptIfCurrent(doc);
        } else if (elapsed >= timeoutMs + abandonGraceMs
                && slot.abandonIfCurrent(doc)) {
            LOG.error("""
                Crawl thread "{}" did not let go of {} after being \
                interrupted. Starting a new thread in its place. Where it \
                is stuck:
                {}""", doc.getThread().getName(), doc.getReference(),
                    stackTrace(doc.getThread()));
            slotRestarter.accept(slot);
            onAbandoned(doc.getThread());
        }
    }

    private void recordTimeout(DocumentInProcess doc) {
        var entry = BeanUtil.clone(doc.getClaimedEntry());
        entry.setProcessingOutcome(ProcessingOutcome.ERROR);
        entry.setProcessingStatus(ProcessingStatus.PROCESSED);
        var ledger = session.getCrawlContext().getCrawlEntryLedger();
        if (doc.isClustered()) {
            if (!ledger.finishClaimedEntry(entry)) {
                LOG.warn("Not recording {} as timed out: it was given to "
                        + "another node.", entry.getReference());
                return;
            }
        } else {
            ledger.updateEntry(entry);
        }
        session.fire(CrawlerEvent.builder()
                .name(CrawlerEvent.REJECTED_ERROR)
                .source(session)
                .crawlSession(session)
                .crawlEntry(entry)
                .exception(new CrawlerException(
                        "Processing took longer than the document timeout ("
                                + timeoutAsText() + ")."))
                .build());
    }

    private synchronized void onAbandoned(Thread thread) {
        abandoned.add(thread);
        abandoned.removeIf(t -> !t.isAlive());
        if (!gaveUp && abandoned.size() >= slots.size()) {
            gaveUp = true;
            LOG.error("""
                {} crawl threads on this node are stuck on documents they \
                were told to give up on. Stopping the crawl: it resumes on \
                the next start.""", abandoned.size());
            session.getCluster().stop();
        }
    }

    private String timeoutAsText() {
        return DurationFormatter.FULL.format(timeoutMs);
    }

    private static String stackTrace(Thread thread) {
        return Arrays.stream(thread.getStackTrace())
                .map(el -> "    at " + el)
                .collect(Collectors.joining("\n"));
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
