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
package com.norconex.crawler.core.cmd.crawl.pipeline.process;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import org.apache.commons.collections4.CollectionUtils;

import com.norconex.crawler.core.cluster.pipeline.BaseStep;
import com.norconex.crawler.core.cluster.pipeline.Step;
import com.norconex.crawler.core.doc.CrawlerDocContext;
import com.norconex.crawler.core.doc.CrawlerDocMetaConstants;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.core.session.CrawlerState;
import com.norconex.crawler.core.util.ConcurrentUtil;
import com.norconex.crawler.core.util.LogUtil;
import com.norconex.importer.doc.Doc;

import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

/**
 * Main crawler task, getting references from the crawl queue, fetch them,
 * and process them until the queue is empty.
 */
@Slf4j
@EqualsAndHashCode
public class CrawlerProcessStep extends BaseStep {

    /**
     * What to do with ALL current entries in the queue.
     */
    public enum ProcessQueueAction {
        CRAWL_ALL, DELETE_ALL
    }

    private final ProcessQueueAction queueAction;
    private BatchDispatcher batchDispatcher;
    // Keep a reference to the session so getProgress() can compute
    // cluster-wide progress
    private volatile CrawlerSession sessionRef;

    public CrawlerProcessStep(String id, ProcessQueueAction queueAction) {
        super(id);
        this.queueAction = queueAction;
    }

    @Override
    public void execute(CrawlerSession session) {
        // store session for progress computations
        sessionRef = session;
        var ctx = session.getCrawlContext();
        if (isStopRequested()) {
            return;
        }
        LOG.info("Processing crawler queue...");
        var cfg = ctx.getCrawlConfig();

        var numThreads = cfg.getNumThreads();

        batchDispatcher = BatchDispatcher.builder()
                .maxBatchSize(cfg.getMaxQueueBatchSize())
                .lowWatermark(Math.max(1,
                        cfg.getMaxQueueBatchSize() / 5))
                .session(session)
                .build();

        var executor = Executors.newFixedThreadPool(
                numThreads, ctx.getThreadFactoryCreator()
                        .create(session.getCrawlerId()));

        try {
            var futures = IntStream.range(0, numThreads)
                    .mapToObj(i -> CompletableFuture
                            .runAsync(() -> {
                                try {
                                    processQueue(session,
                                            i);
                                } catch (Exception e) {
                                    LOG.error(
                                            "Problem running task {} {} of {}.",
                                            "crawl-" + session
                                                    .getCrawlerId(),
                                            i,
                                            numThreads,
                                            e);
                                    throw new CompletionException(
                                            e);
                                }
                            }, executor))
                    .toList();
            CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]))
                    .join();
        } finally {
            // Also when a task failed: the pool's threads would otherwise
            // stay parked, and keep the JVM from exiting.
            ConcurrentUtil.cleanShutdown(executor);
        }
    }

    @Override
    public Step.StepProgress getProgress() {
        // Only compute for active session; otherwise unknown
        var s = sessionRef;
        if (s == null) {
            return null;
        }
        var ledger = s.getCrawlContext().getCrawlEntryLedger();
        var processed = ledger.getProcessedCount();
        var queued = ledger.getQueuedEntryCount();
        var denom = processed + queued;
        var progress =
                denom <= 0 ? 0.0f
                        : (float) (processed
                                / (double) denom);
        var msg = "processed=" + processed + ", queued=" + queued;
        return new Step.StepProgress(progress, msg);
    }

    //TODO get a batch of URLs (up to X, configurable)
    //TODO have coordinator periodically cleanup staled references via
    // scheduler (or after each X docs?).

    // just invoked in its own thread
    void processQueue(CrawlerSession session, int threadIndex) {
        var nodeName = session.getCluster().getLocalNode()
                .getNodeName();
        LOG.debug("[{}] processQueue(threadIndex={}) starting.",
                nodeName, threadIndex);
        LOG.debug("[{}] initial queueCount={} processingCount={} "
                + "processedCount= {}.",
                nodeName,
                session.getCrawlContext().getCrawlEntryLedger()
                        .getQueuedEntryCount(),
                session.getCrawlContext().getCrawlEntryLedger()
                        .getProcessingCount(),
                session.getCrawlContext().getCrawlEntryLedger()
                        .getProcessedCount());
        LOG.debug("Crawler thread #{} starting...", threadIndex);
        Thread.currentThread()
                .setName(session.getCrawlerId() + "#"
                        + threadIndex);
        LogUtil.setMdcCrawlerId(session.getCrawlerId());

        try {
            var activityChecker = new CrawlerActivityChecker(
                    session,
                    queueAction == ProcessQueueAction.DELETE_ALL);
            while (!isStopRequested()) {
                if (!activityChecker.canContinue()) {
                    LOG.trace("[{}] processQueue(threadIndex={}) "
                            + "stopping: canContinue() is false.",
                            nodeName, threadIndex);
                    break;
                }
                if (!processNextInQueue(session,
                        activityChecker)) {
                    LOG.trace("[{}] processQueue(threadIndex={}) "
                            + "stopping: processNextInQueue returned false.",
                            nodeName, threadIndex);
                    break;
                }
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("[{}] processQueue(threadIndex={}) exiting. "
                        + "queueCount={} processingCount={} processedCount={}.",
                        nodeName, threadIndex,
                        session.getCrawlContext()
                                .getCrawlEntryLedger()
                                .getQueuedEntryCount(),
                        session.getCrawlContext()
                                .getCrawlEntryLedger()
                                .getProcessingCount(),
                        session.getCrawlContext()
                                .getCrawlEntryLedger()
                                .getProcessedCount());
            }
        } catch (com.hazelcast.core.HazelcastInstanceNotActiveException e) {
            LOG.info("Hazelcast instance became inactive while processing "
                    + "queue on node {}. Treating as graceful stop.",
                    nodeName);
        } catch (Exception e) {
            LOG.error("Problem in thread execution.", e);
        }
    }

    private boolean processNextInQueue(
            CrawlerSession session,
            CrawlerActivityChecker activityChecker) {

        var crawlCtx = session.getCrawlContext();
        var docProcessCtx = new ProcessContext().crawlSession(session);
        var nodeName = session.getCluster().getLocalNode()
                .getNodeName();
        try {
            var currentEntry = batchDispatcher.take();

            if (LOG.isTraceEnabled()) {
                LOG.trace("[{}] processNextInQueue pulled entry {}.",
                        nodeName,
                        currentEntry == null
                                ? "<null>"
                                : currentEntry.getReference());
            }

            if (currentEntry == null) {
                LOG.trace("[{}] processNextInQueue got null entry from "
                        + "dispatcher. Checking isActive()...",
                        nodeName);
                var active = activityChecker.isActive();
                LOG.trace("[{}] activityChecker.isActive() returned {}.",
                        nodeName, active);
                return active;
            }

            LOG.trace("[{}] processNextInQueue processing ref={}.",
                    nodeName,
                    currentEntry.getReference());

            var doc = new Doc(currentEntry.getReference()); //NOSONAR
            CrawlerEntry previousEntry = null;
            if (session.isIncremental()) {
                previousEntry = crawlCtx
                        .getCrawlEntryLedger()
                        .getBaselineEntry(currentEntry
                                .getReference())
                        .orElse(null);
            }

            doc.getMetadata().set(
                    CrawlerDocMetaConstants.IS_DOC_NEW,
                    previousEntry == null);

            var docContext = CrawlerDocContext.builder()
                    .currentCrawlEntry(currentEntry)
                    .previousCrawlEntry(previousEntry)
                    .doc(doc)
                    .build();
            docProcessCtx.docContext(docContext);

            // Before document processing
            if (doc != null) {
                session.fire(CrawlerEvent.builder()
                        .name(CrawlerEvent.DOCUMENT_PROCESSING_BEGIN)
                        .crawlSession(session)
                        .source(doc)
                        .build());
            }

            if (activityChecker.isDeleting()) {
                ProcessDelete.execute(docProcessCtx);
            } else {
                ProcessUpsert.execute(docProcessCtx);
            }

            // After document processing
            var processedDoc = docProcessCtx.docContext().getDoc();
            if (processedDoc != null) {
                session.fire(CrawlerEvent.builder()
                        .name(CrawlerEvent.DOCUMENT_PROCESSING_END)
                        .crawlSession(session)
                        .source(processedDoc)
                        .build());
            }
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // If stop was requested while a document was being processed,
            // leave it in PROCESSING state rather than finalizing it as
            // PROCESSED-ERROR. This allows requeueProcessingEntries() to
            // correctly restore it to QUEUED on the next resume.
            var stopInProgress = isStopRequested()
                    || session.getCrawlState() == CrawlerState.STOPPED;
            if (stopInProgress
                    && docProcessCtx.docContext() != null) {
                LOG.info("Stop requested during processing of '{}'; "
                        + "leaving in PROCESSING state for resume.",
                        docProcessCtx.docContext()
                                .getReference());
                docProcessCtx.finalized(true);
                return false;
            }
            if (handleExceptionAndCheckIfStopCrawler(
                    session, docProcessCtx, e)) {
                session.getCluster().stop();
                return false;
            }
        } finally {
            ProcessFinalize.execute(docProcessCtx);
        }
        return true;
    }

    // true to stop crawler
    private boolean handleExceptionAndCheckIfStopCrawler(
            CrawlerSession session,
            ProcessContext docProcessCtx, Exception e) {

        var crawlCtx = session.getCrawlContext();

        //TODO check nested exception for a match.

        var stopTheCrawler = true;
        // if an exception was thrown and there is no CrawlDocRecord we
        // stop the crawler since it means we can't no longer read for the
        // queue, and we can no longer fetch a next document, possibly leading
        // to an infinite loop if it keeps trying and failing.
        var docContext = docProcessCtx.docContext();
        if (docContext == null) {
            LOG.error("An unrecoverable error was detected. The crawler will "
                    + "stop.", e);
            crawlCtx.getEventManager().fire(
                    CrawlerEvent.builder()
                            .name(CrawlerEvent.CRAWLER_ERROR)
                            .source(session)
                            .exception(e)
                            .build());
            return stopTheCrawler;
        }

        docContext.getCurrentCrawlEntry()
                .setProcessingOutcome(ProcessingOutcome.ERROR);
        if (LOG.isDebugEnabled()) {
            LOG.info("Could not process document: {} ({})",
                    docProcessCtx.docContext()
                            .getReference(),
                    e.getMessage(),
                    e);
        } else {
            LOG.info("Could not process document: {} ({})",
                    docProcessCtx.docContext()
                            .getReference(),
                    e.getMessage());
        }
        crawlCtx.getEventManager().fire(
                CrawlerEvent.builder()
                        .name(CrawlerEvent.REJECTED_ERROR)
                        .source(session)
                        .crawlEntry(docContext
                                .getCurrentCrawlEntry())
                        .exception(e)
                        .build());
        ProcessFinalize.execute(docProcessCtx);

        // Rethrow exception if we want the crawler to stop
        var exceptionClasses = crawlCtx.getCrawlConfig()
                .getStopOnExceptions();
        if (CollectionUtils.isNotEmpty(exceptionClasses)) {
            for (Class<? extends Exception> c : exceptionClasses) {
                if (c.isAssignableFrom(e.getClass())) {
                    LOG.error("Encountered a crawler-stopping exception as "
                            + "per configuration.",
                            e);
                    return stopTheCrawler;
                }
            }
        }
        LOG.error("""
                Encountered the following crawler exception and attempting\s\
                to ignore it. To force the crawler to stop upon encountering\s\
                this exception, use the "stopOnExceptions" feature\s\
                of your crawler config.""", e);
        return !stopTheCrawler;
    }
}
