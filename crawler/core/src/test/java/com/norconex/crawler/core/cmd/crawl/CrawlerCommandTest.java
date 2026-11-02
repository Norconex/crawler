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
package com.norconex.crawler.core.cmd.crawl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.norconex.crawler.core.cluster.Cluster;
import com.norconex.crawler.core.cluster.ClusterNode;
import com.norconex.crawler.core.cluster.pipeline.Pipeline;
import com.norconex.crawler.core.cluster.pipeline.PipelineManager;
import com.norconex.crawler.core.cluster.pipeline.PipelineResult;
import com.norconex.crawler.core.cluster.pipeline.PipelineStatus;
import com.norconex.crawler.core.cmd.crawl.pipeline.CrawlerPipelineFactory;
import com.norconex.crawler.core.cmd.crawl.pipeline.bootstrap.CrawlerBootstrapper;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.core.session.CrawlerState;

@Timeout(30)
class CrawlerCommandTest {

    @ParameterizedTest
    @CsvSource(
        {
                "COMPLETED,COMPLETED",
                "STOPPED,STOPPED",
                "FAILED,FAILED",
                "EXPIRED,FAILED",
                "STOPPING,FAILED",
                "PENDING,FAILED",
                "RUNNING,FAILED" }
    )
    void execute_updatesCrawlStateFromPipelineStatus(
            PipelineStatus pipelineStatus, CrawlerState expectedState) {
        var fixture = newFixture(false, List.of(), null);
        when(fixture.pipelineManager.executePipeline(fixture.pipeline))
                .thenReturn(CompletableFuture.completedFuture(
                        PipelineResult.builder()
                                .pipelineId(fixture.pipeline.getId())
                                .status(pipelineStatus)
                                .build()));

        var threadName = Thread.currentThread().getName();
        try {
            fixture.command.execute(fixture.session);
        } finally {
            Thread.currentThread().setName(threadName);
        }

        verify(fixture.cluster).startStopMonitoring();
        verify(fixture.session).updateCrawlState(expectedState);
        verify(fixture.session).fire("CRAWLER_CRAWL_BEGIN", fixture.session);
        verify(fixture.session).fire("CRAWLER_CRAWL_END", fixture.session);
        verify(fixture.session, never()).setPostCloseCleanup(any());
    }

    @Test
    void execute_runsBootstrappersOncePerRun() {
        var bootstrapper = mock(CrawlerBootstrapper.class);
        var fixture = newFixture(false, List.of(bootstrapper), null);
        when(fixture.pipelineManager.executePipeline(fixture.pipeline))
                .thenReturn(CompletableFuture.completedFuture(
                        PipelineResult.builder()
                                .pipelineId(fixture.pipeline.getId())
                                .status(PipelineStatus.COMPLETED)
                                .build()));

        var threadName = Thread.currentThread().getName();
        try {
            fixture.command.execute(fixture.session);
        } finally {
            Thread.currentThread().setName(threadName);
        }

        verify(fixture.session).oncePerRun(anyString(), any(Runnable.class));
        verify(bootstrapper).bootstrap(fixture.session);
    }

    @Test
    void execute_cancellationException_marksStopped() {
        var fixture = newFixture(false, List.of(), null);
        when(fixture.pipelineManager.executePipeline(fixture.pipeline))
                .thenReturn(CompletableFuture.failedFuture(
                        new CancellationException("stop requested")));

        var threadName = Thread.currentThread().getName();
        try {
            fixture.command.execute(fixture.session);
        } finally {
            Thread.currentThread().setName(threadName);
        }

        verify(fixture.session).updateCrawlState(CrawlerState.STOPPED);
    }

    @Test
    void execute_wrappedInterruptedException_marksStoppedAndRestoresInterrupt() {
        var fixture = newFixture(false, List.of(), null);
        when(fixture.pipelineManager.executePipeline(fixture.pipeline))
                .thenReturn(CompletableFuture.failedFuture(
                        new CompletionException(
                                new InterruptedException("interrupted"))));

        var threadName = Thread.currentThread().getName();
        try {
            fixture.command.execute(fixture.session);
            verify(fixture.session).updateCrawlState(CrawlerState.STOPPED);
            org.assertj.core.api.Assertions
                    .assertThat(Thread.currentThread().isInterrupted())
                    .isTrue();
        } finally {
            Thread.interrupted();
            Thread.currentThread().setName(threadName);
        }
    }

    @Test
    void execute_withMaxDuration_usesTimedFutureGetPath() {
        var fixture = newFixture(false, List.of(), Duration.ofMinutes(1));
        when(fixture.pipelineManager.executePipeline(fixture.pipeline))
                .thenReturn(CompletableFuture.completedFuture(
                        PipelineResult.builder()
                                .pipelineId(fixture.pipeline.getId())
                                .status(PipelineStatus.COMPLETED)
                                .build()));

        var threadName = Thread.currentThread().getName();
        try {
            fixture.command.execute(fixture.session);
        } finally {
            Thread.currentThread().setName(threadName);
        }

        verify(fixture.session).updateCrawlState(CrawlerState.COMPLETED);
    }

    @SuppressWarnings("unchecked")
    private Fixture newFixture(boolean coordinator,
            List<CrawlerBootstrapper> bootstrappers,
            Duration maxCrawlDuration) {
        var pipelineFactory = mock(CrawlerPipelineFactory.class);
        var session = mock(CrawlerSession.class);
        var context = mock(CrawlerContext.class);
        var cluster = mock(Cluster.class);
        var localNode = mock(ClusterNode.class);
        var pipelineManager = mock(PipelineManager.class);
        var crawlConfig = mock(com.norconex.crawler.core.CrawlerConfig.class);

        var pipeline = new Pipeline("pipe-1");
        var command = new CrawlerCommand(pipelineFactory);

        when(session.getCrawlContext()).thenReturn(context);
        when(session.getCluster()).thenReturn(cluster);
        when(context.getCrawlConfig()).thenReturn(crawlConfig);
        when(context.getBootstrappers()).thenReturn(bootstrappers);
        when(context.getId()).thenReturn("crawler-1");
        when(crawlConfig.getMaxCrawlDuration()).thenReturn(maxCrawlDuration);
        when(cluster.getLocalNode()).thenReturn(localNode);
        when(cluster.getPipelineManager()).thenReturn(pipelineManager);
        when(localNode.isCoordinator()).thenReturn(coordinator);
        when(pipelineFactory.create(session)).thenReturn(pipeline);

        doAnswer(invocation -> {
            var runnable = invocation.getArgument(1, Runnable.class);
            runnable.run();
            return null;
        }).when(session).oncePerRun(anyString(), any(Runnable.class));

        doAnswer(invocation -> {
            var supplier = (Supplier<CrawlerState>) invocation.getArgument(1);
            return supplier.get();
        }).when(session).oncePerRunAndGet(anyString(), any(Supplier.class));

        return new Fixture(command, session, context, cluster, localNode,
                pipelineManager, pipeline);
    }

    private record Fixture(
            CrawlerCommand command,
            CrawlerSession session,
            CrawlerContext context,
            Cluster cluster,
            ClusterNode localNode,
            PipelineManager pipelineManager,
            Pipeline pipeline) {
    }
}
