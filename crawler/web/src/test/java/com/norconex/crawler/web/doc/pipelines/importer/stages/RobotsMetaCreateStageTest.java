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
package com.norconex.crawler.web.doc.pipelines.importer.stages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.crawler.core.CrawlerException;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.doc.CrawlerDocContext;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.web.WebCrawlerConfig;
import com.norconex.crawler.web.doc.operations.robot.RobotsMeta;
import com.norconex.crawler.web.doc.operations.robot.RobotsMetaProvider;
import com.norconex.crawler.web.doc.pipelines.importer.WebImporterPipelineContext;
import com.norconex.crawler.web.ledger.WebCrawlerEntry;
import com.norconex.crawler.web.stubs.CrawlDocStubs;

@Timeout(30)
class RobotsMetaCreateStageTest {

    private CrawlerSession session;
    private WebCrawlerConfig webConfig;

    @BeforeEach
    void setUp() {
        session = mock(CrawlerSession.class);
        var crawlerCtx = mock(CrawlerContext.class);
        webConfig = new WebCrawlerConfig();
        when(session.getCrawlContext()).thenReturn(crawlerCtx);
        when(crawlerCtx.getCrawlConfig()).thenReturn(webConfig);
    }

    private WebImporterPipelineContext buildContext(String ref,
            String content) {
        var entry = new WebCrawlerEntry(ref);
        var docCtx = CrawlerDocContext.builder()
                .doc(CrawlDocStubs.crawlDocHtml(ref, content))
                .currentCrawlEntry(entry)
                .build();
        return new WebImporterPipelineContext(session, docCtx);
    }

    @Test
    void nullProviderSkipsProcessingAndReturnsTrue() {
        webConfig.setRobotsMetaProvider(null);
        var ctx = buildContext("http://example.com", "<html/>");

        assertThat(new RobotsMetaCreateStage().executeStage(ctx)).isTrue();
        assertThat(ctx.getRobotsMeta()).isNull();
    }

    @Test
    void providerReturningNullMeta_noEventFired() throws IOException {
        RobotsMetaProvider provider = mock(RobotsMetaProvider.class);
        when(provider.getRobotsMeta(any(), any(), any(), any()))
                .thenReturn(null);
        webConfig.setRobotsMetaProvider(provider);
        var ctx = buildContext("http://example.com",
                "<html><head></head><body/></html>");

        assertThat(new RobotsMetaCreateStage().executeStage(ctx)).isTrue();
        assertThat(ctx.getRobotsMeta()).isNull();
        verify(session, never()).fire(any());
    }

    @Test
    void providerReturningMeta_eventFired() throws IOException {
        var meta = new RobotsMeta(false, false);
        RobotsMetaProvider provider = mock(RobotsMetaProvider.class);
        when(provider.getRobotsMeta(any(), any(), any(), any()))
                .thenReturn(meta);
        webConfig.setRobotsMetaProvider(provider);
        var ctx = buildContext("http://example.com",
                "<html><head><meta name=\"robots\" content=\"index\"/>"
                        + "</head><body/></html>");

        assertThat(new RobotsMetaCreateStage().executeStage(ctx)).isTrue();
        assertThat(ctx.getRobotsMeta()).isSameAs(meta);
        verify(session).fire(any());
    }

    @Test
    void ioExceptionWrappedAsCrawlerException() throws IOException {
        RobotsMetaProvider provider = mock(RobotsMetaProvider.class);
        when(provider.getRobotsMeta(any(), any(), any(), any()))
                .thenThrow(new IOException("simulated read error"));
        webConfig.setRobotsMetaProvider(provider);
        var ctx = buildContext("http://example.com", "<html/>");

        assertThatExceptionOfType(CrawlerException.class)
                .isThrownBy(() -> new RobotsMetaCreateStage().executeStage(ctx))
                .withCauseInstanceOf(IOException.class);
    }
}
