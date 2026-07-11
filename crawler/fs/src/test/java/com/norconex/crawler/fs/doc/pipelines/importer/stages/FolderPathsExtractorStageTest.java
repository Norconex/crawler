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
package com.norconex.crawler.fs.doc.pipelines.importer.stages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.CrawlerConfig.ChangeDiscovery;
import com.norconex.crawler.core.CrawlerException;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.doc.CrawlerDocContext;
import com.norconex.crawler.core.doc.pipelines.CrawlerDocPipelines;
import com.norconex.crawler.core.doc.pipelines.importer.ImporterPipelineContext;
import com.norconex.crawler.core.doc.pipelines.queue.QueuePipeline;
import com.norconex.crawler.core.fetch.FetchDirective;
import com.norconex.crawler.core.fetch.FetchDirectiveSupport;
import com.norconex.crawler.core.fetch.FetchException;
import com.norconex.crawler.core.fetch.Fetcher;
import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.ledger.CrawlerEntryLedger;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.fs.fetch.FolderPathsFetchResponse;
import com.norconex.crawler.fs.fetch.FsPath;
import com.norconex.crawler.fs.ledger.FsCrawlerEntry;
import com.norconex.importer.doc.Doc;

@Timeout(30)
class FolderPathsExtractorStageTest {

        /**
         * Builds an {@link ImporterPipelineContext} backed by mocks,
         * wiring together CrawlerSession → CrawlerContext → CrawlerConfig/Fetcher/
         * DocPipelines, and a CrawlerDocContext wrapping the given entry.
         */
        private ImporterPipelineContext buildCtx(
                        FsCrawlerEntry entry,
                        Fetcher fetcher,
                        QueuePipeline queuePipeline) {
                return buildCtx(entry, fetcher, queuePipeline,
                                ChangeDiscovery.CRAWLER_SCAN, false, null);
        }

        private ImporterPipelineContext buildCtx(
                        FsCrawlerEntry entry,
                        Fetcher fetcher,
                        QueuePipeline queuePipeline,
                        ChangeDiscovery changeDiscovery,
                        boolean incremental,
                        CrawlerEntryLedger ledger) {

                var config = new CrawlerConfig()
                                .setDocumentFetchSupport(
                                                FetchDirectiveSupport.REQUIRED)
                                .setChangeDiscovery(changeDiscovery);

                var docPipelines = CrawlerDocPipelines.builder()
                                .queuePipeline(queuePipeline)
                                .build();

                var crawlContext = mock(CrawlerContext.class);
                when(crawlContext.getCrawlConfig()).thenReturn(config);
                when(crawlContext.getFetcher()).thenReturn(fetcher);
                when(crawlContext.getDocPipelines()).thenReturn(docPipelines);
                when(crawlContext.getCrawlEntryLedger()).thenReturn(ledger);
                when(crawlContext.createCrawlEntry(any()))
                                .thenAnswer(inv -> new FsCrawlerEntry(
                                                inv.getArgument(0,
                                                                String.class)));

                var session = mock(CrawlerSession.class);
                when(session.getCrawlContext()).thenReturn(crawlContext);
                when(session.isIncremental()).thenReturn(incremental);

                var docContext = CrawlerDocContext.builder()
                                .doc(new Doc(entry.getReference()))
                                .currentCrawlEntry(entry)
                                .build();

                return new ImporterPipelineContext(session, docContext);
        }

        @Test
        void testFetchExceptionWrapped() {
                var entry = new FsCrawlerEntry("file:///some/folder");
                entry.setFolder(true);

                var fetcher = mock(Fetcher.class);
                try {
                        when(fetcher.fetch(any()))
                                        .thenThrow(new FetchException("blah"));
                } catch (FetchException e) {
                        throw new AssertionError(
                                        "Unexpected exception in mock setup",
                                        e);
                }

                var ctx = buildCtx(
                                entry,
                                fetcher,
                                mock(QueuePipeline.class));

                assertThatExceptionOfType(CrawlerException.class)
                                .isThrownBy(() -> //NOSONAR
                                new FolderPathsExtractorStage(
                                                FetchDirective.DOCUMENT)
                                                                .test(ctx))
                                .withMessageContaining(
                                                "Could not fetch child paths of:");
        }

        @Test
        void testFolderChildPathsQueued() {
                var entry = new FsCrawlerEntry("file:///some/folder");
                entry.setFolder(true);

                var child1 = new FsPath("file:///some/folder/child1.txt");
                child1.setFile(true);
                var child2 = new FsPath("file:///some/folder/sub");
                child2.setFolder(true);

                var mockResponse = mock(FolderPathsFetchResponse.class);
                when(mockResponse.getProcessingOutcome())
                                .thenReturn(ProcessingOutcome.NEW);
                when(mockResponse.getChildPaths())
                                .thenReturn(Set.of(child1, child2));

                var fetcher = mock(Fetcher.class);
                try {
                        when(fetcher.fetch(any())).thenReturn(mockResponse);
                } catch (FetchException e) {
                        throw new AssertionError(
                                        "Unexpected exception in mock setup",
                                        e);
                }

                // A no-op queue pipeline — we only assert on the return value
                var queuePipeline = mock(QueuePipeline.class);

                var ctx = buildCtx(entry, fetcher, queuePipeline);

                // A folder-only entry returns false (no file content to process)
                boolean result = new FolderPathsExtractorStage(
                                FetchDirective.DOCUMENT).test(ctx);
                assertThat(result).isFalse();
        }

        @Test
        void testFolderAndFileEntryReturnsContinue() {
                // An entry that is both a folder and a file should return true
                var entry = new FsCrawlerEntry("file:///some/folderfile");
                entry.setFolder(true);
                entry.setFile(true);

                var mockResponse = mock(FolderPathsFetchResponse.class);
                when(mockResponse.getProcessingOutcome())
                                .thenReturn(ProcessingOutcome.NEW);
                when(mockResponse.getChildPaths()).thenReturn(Set.of());

                var fetcher = mock(Fetcher.class);
                try {
                        when(fetcher.fetch(any())).thenReturn(mockResponse);
                } catch (FetchException e) {
                        throw new AssertionError(
                                        "Unexpected exception in mock setup",
                                        e);
                }

                var ctx = buildCtx(entry, fetcher, mock(QueuePipeline.class));

                boolean result = new FolderPathsExtractorStage(
                                FetchDirective.DOCUMENT).test(ctx);
                assertThat(result).isTrue();
        }

        @Test
        void testSourceDeltaMissingFolderQueuesKnownDescendants() {
                var entry = new FsCrawlerEntry(
                                "m365od://tenant/users/user123/drives/drive123");
                entry.setFolder(true);

                var mockResponse = mock(FolderPathsFetchResponse.class);
                when(mockResponse.getProcessingOutcome())
                                .thenReturn(ProcessingOutcome.NOT_FOUND);
                when(mockResponse.getChildPaths()).thenReturn(Set.of());

                var fetcher = mock(Fetcher.class);
                try {
                        when(fetcher.fetch(any())).thenReturn(mockResponse);
                } catch (FetchException e) {
                        throw new AssertionError(
                                        "Unexpected exception in mock setup",
                                        e);
                }

                var ledger = mock(CrawlerEntryLedger.class);
                doAnswer(inv -> {
                        @SuppressWarnings("unchecked")
                        Consumer<CrawlerEntry> consumer = inv.getArgument(0,
                                        Consumer.class);

                        consumer.accept(new FsCrawlerEntry(
                                        "m365od://tenant/users/user123/drives/drive123/items/fileA"));
                        consumer.accept(new FsCrawlerEntry(
                                        "m365od://tenant/users/user123/drives/drive123/items/folderB"));
                        consumer.accept(new FsCrawlerEntry(
                                        "m365od://tenant/users/user123/drives/otherDrive/items/skipMe"));
                        return null;
                }).when(ledger).forEachBaseline(any());

                var ctx = buildCtx(entry, fetcher, mock(QueuePipeline.class),
                                ChangeDiscovery.SOURCE_DELTA, true, ledger);

                boolean result = new FolderPathsExtractorStage(
                                FetchDirective.DOCUMENT).test(ctx);

                assertThat(result).isFalse();
                assertThat(entry.getProcessingOutcome())
                                .isEqualTo(ProcessingOutcome.NOT_FOUND);
                verify(ledger, times(2)).queue(any(FsCrawlerEntry.class));
        }

        @Test
        void testMissingFolderOutsideSourceDeltaDoesNotQueueDescendants() {
                var entry = new FsCrawlerEntry(
                                "m365od://tenant/users/user123/drives/drive123");
                entry.setFolder(true);

                var mockResponse = mock(FolderPathsFetchResponse.class);
                when(mockResponse.getProcessingOutcome())
                                .thenReturn(ProcessingOutcome.NOT_FOUND);
                when(mockResponse.getChildPaths()).thenReturn(Set.of());

                var fetcher = mock(Fetcher.class);
                try {
                        when(fetcher.fetch(any())).thenReturn(mockResponse);
                } catch (FetchException e) {
                        throw new AssertionError(
                                        "Unexpected exception in mock setup",
                                        e);
                }

                var ledger = mock(CrawlerEntryLedger.class);

                var ctx = buildCtx(entry, fetcher, mock(QueuePipeline.class),
                                ChangeDiscovery.CRAWLER_SCAN, true, ledger);

                boolean result = new FolderPathsExtractorStage(
                                FetchDirective.DOCUMENT).test(ctx);

                assertThat(result).isFalse();
                assertThat(entry.getProcessingOutcome())
                                .isEqualTo(ProcessingOutcome.NOT_FOUND);
                verify(ledger, never()).forEachBaseline(any());
                verify(ledger, never()).queue(any());
        }

        @Test
        void testNonFolderEntrySkipsAndReturnsFile() {
                // A file-only entry must skip folder logic and return isFile()
                var entry = new FsCrawlerEntry("file:///some/file.txt");
                entry.setFile(true);

                var fetcher = mock(Fetcher.class);

                var ctx = buildCtx(entry, fetcher, mock(QueuePipeline.class));

                boolean result = new FolderPathsExtractorStage(
                                FetchDirective.DOCUMENT).test(ctx);
                assertThat(result).isTrue();
        }
}
