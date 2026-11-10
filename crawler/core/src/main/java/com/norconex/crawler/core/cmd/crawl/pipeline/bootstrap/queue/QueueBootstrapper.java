/* Copyright 2023-2026 Norconex Inc.
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

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.collections4.CollectionUtils;

import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.cmd.crawl.pipeline.bootstrap.CrawlerBootstrapper;
import com.norconex.crawler.core.doc.pipelines.queue.QueuePipelineContext;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.core.util.LogUtil;

import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Default queue initializer, feeding the queue from a mix of reference
 * enqueuers. Default enqueuers are:
 * </p>
 * <ul>
 *   <li>{@link RefListEnqueuer} (list of references)</li>
 *   <li>{@link RefFileEnqueuer} (files containing references)</li>
 *   <li>{@link RefProviderEnqueuer} (Java-based reference providers)</li>
 * </ul>
 * <p>
 * Those are configurable in {@link CrawlerConfig}.
 * </p>
 */
@Slf4j
@EqualsAndHashCode
//TODO XXX rename this if no longer a "bootstrapper" as we are moving the logic back to pipeline
public class QueueBootstrapper implements CrawlerBootstrapper {

    //TODO XXX ******** make summary about crawl run, just total process for session? Or show delta? ***********

    private final List<ReferenceEnqueuer> enqueuers = new ArrayList<>();

    public QueueBootstrapper() {
        enqueuers.add(new RefListEnqueuer());
        enqueuers.add(new RefFileEnqueuer());
        enqueuers.add(new RefProviderEnqueuer());
    }

    public QueueBootstrapper(
            List<ReferenceEnqueuer> enqueuers) {
        if (CollectionUtils.isNotEmpty(enqueuers)) {
            this.enqueuers.addAll(enqueuers);
        }
    }

    /**
     * Queues the start references, unless a previous run of this crawl
     * session already queued them all. A run resuming a crawl whose
     * queueing was left unfinished (stopped, failed or crashed) queues them
     * again: references already known to the crawl are ignored.
     * @param session the crawl session
     */
    @Override
    public void bootstrap(CrawlerSession session) {
        if (session.isResumed()) {
            if (session.isStartRefsAllQueued()) {
                LOG.info("Resuming: start references were all queued by a "
                        + "previous run. Continuing with the entries restored "
                        + "from the ledger.");
                session.setStartRefsQueueingComplete(true);
                return;
            }
            LOG.info("Resuming: a previous run did not finish queueing "
                    + "start references. Queueing them again (references "
                    + "already known are ignored).");
        } else {
            session.setStartRefsAllQueued(false);
        }

        session.markStartRefsQueueingNode();
        var async = session.getCrawlContext()
                .getCrawlConfig().isStartReferencesAsync();
        LOG.info("Queueing start references ({})...",
                async ? "asynchronously" : "synchronously");
        var queueInitContext = new QueueBootstrapContext(
                session,
                docCtx -> queue(new QueuePipelineContext(session, docCtx)));

        if (async) {
            initializeQueueAsync(queueInitContext);
        } else {
            runEnqueuers(queueInitContext);
        }
    }

    private void runEnqueuers(QueueBootstrapContext ctx) {
        var session = ctx.getCrawlSession();
        var allQueued = false;
        try {
            var cnt = 0;
            for (var enqueuer : enqueuers) {
                if (session.isStopRequested()) {
                    break;
                }
                cnt += enqueuer.enqueue(ctx);
            }
            allQueued = !session.isStopRequested();
            if (allQueued) {
                LOG.info("{} start references identified.",
                        NumberFormat.getNumberInstance().format(cnt));
            } else {
                LOG.info("Queueing of start references stopped after {} "
                        + "references. It resumes with the crawl.",
                        NumberFormat.getNumberInstance().format(cnt));
            }
        } finally {
            if (allQueued) {
                session.setStartRefsAllQueued(true);
            }
            // Whether queueing finished, was stopped or failed, this run
            // must not wait for it any longer.
            session.setStartRefsQueueingComplete(true);
        }
    }

    private void queue(QueuePipelineContext context) {
        context.getCrawlSession()
                .getCrawlContext()
                .getDocPipelines()
                .getQueuePipeline()
                .accept(context);
    }

    private void initializeQueueAsync(QueueBootstrapContext ctx) {
        var executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                LogUtil.setMdcCrawlerId(ctx.getCrawlSession().getCrawlerId());
                Thread.currentThread()
                        .setName(ctx.getCrawlSession().getCrawlerId());
                try {
                    runEnqueuers(ctx);
                } catch (RuntimeException e) {
                    LOG.error("Could not queue all start references. "
                            + "Queueing resumes with the crawl.", e);
                }
            });
        } finally {
            try {
                executor.shutdown();
                // Reduced from 5s to 2s for faster shutdown
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                LOG.error("Reading of start references interrupted.", e);
                Thread.currentThread().interrupt();
            }
        }
    }
}
