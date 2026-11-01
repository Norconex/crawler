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
package com.norconex.crawler.core.cmd.crawl.pipeline.bootstrap.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;

import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.CrawlerException;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.session.CrawlerSession;

/**
 * Tests for {@link RefFileEnqueuer}.
 */
@Timeout(30)
@SuppressWarnings("null")
class RefFileEnqueuerTest {

    @TempDir
    private Path tempDir;

    // Helper: create a QueueBootstrapContext with the given config
    private QueueBootstrapContext buildContext(
            CrawlerConfig config, List<CrawlerEntry> queued) throws Exception {
        var session = mock(CrawlerSession.class);
        var crawlContext = mock(CrawlerContext.class);
        when(session.getCrawlContext()).thenReturn(crawlContext);
        when(crawlContext.getCrawlConfig()).thenReturn(config);
        when(crawlContext.createCrawlEntry(anyString()))
                .thenAnswer(inv -> new CrawlerEntry(inv.getArgument(0)));
        return new QueueBootstrapContext(session, entry -> queued.add(entry));
    }

    // -----------------------------------------------------------------
    // Basic enqueue scenarios
    // -----------------------------------------------------------------

    @Test
    void emptyFile_returnsZero() throws Exception {
        var refsFile = tempDir.resolve("empty.txt");
        Files.writeString(refsFile, "");

        var config = new CrawlerConfig();
        config.setStartReferencesFiles(List.of(refsFile));

        var queued = new ArrayList<CrawlerEntry>();
        var ctx = buildContext(config, queued);

        var result = new RefFileEnqueuer().enqueue(ctx);

        assertThat(result).isZero();
        assertThat(queued).isEmpty();
    }

    @Test
    void fileWithRefs_queuesEachRef() throws Exception {
        var refsFile = tempDir.resolve("refs.txt");
        Files.writeString(refsFile, "http://a.com\nhttp://b.com\nhttp://c.com");

        var config = new CrawlerConfig();
        config.setStartReferencesFiles(List.of(refsFile));

        var queued = new ArrayList<CrawlerEntry>();
        var ctx = buildContext(config, queued);

        var result = new RefFileEnqueuer().enqueue(ctx);

        assertThat(result).isEqualTo(3);
        assertThat(queued).extracting(CrawlerEntry::getReference)
                .containsExactly(
                        "http://a.com", "http://b.com", "http://c.com");
    }

    @Test
    void fileWithCommentAndBlankLines_skipsThose() throws Exception {
        var content = """
                # this is a comment
                http://valid.com

                  \t
                # another comment
                http://also-valid.com
                """;
        var refsFile = tempDir.resolve("with-comments.txt");
        Files.writeString(refsFile, content);

        var config = new CrawlerConfig();
        config.setStartReferencesFiles(List.of(refsFile));

        var queued = new ArrayList<CrawlerEntry>();
        var ctx = buildContext(config, queued);

        var result = new RefFileEnqueuer().enqueue(ctx);

        assertThat(result).isEqualTo(2);
        assertThat(queued).extracting(CrawlerEntry::getReference)
                .containsExactly("http://valid.com", "http://also-valid.com");
    }

    @Test
    void multipleFiles_queuesFromAll() throws Exception {
        var file1 = tempDir.resolve("file1.txt");
        var file2 = tempDir.resolve("file2.txt");
        Files.writeString(file1, "http://site1.com");
        Files.writeString(file2, "http://site2.com\nhttp://site3.com");

        var config = new CrawlerConfig();
        config.setStartReferencesFiles(List.of(file1, file2));

        var queued = new ArrayList<CrawlerEntry>();
        var ctx = buildContext(config, queued);

        var result = new RefFileEnqueuer().enqueue(ctx);

        assertThat(result).isEqualTo(3);
        assertThat(queued).extracting(CrawlerEntry::getReference)
                .containsExactlyInAnyOrder(
                        "http://site1.com",
                        "http://site2.com",
                        "http://site3.com");
    }

    // -----------------------------------------------------------------
    // Error handling
    // -----------------------------------------------------------------

    @Test
    void nonExistentFile_throwsCrawlerException() throws Exception {
        var nonExistent = tempDir.resolve("does-not-exist.txt");

        var config = new CrawlerConfig();
        config.setStartReferencesFiles(List.of(nonExistent));

        var queued = new ArrayList<CrawlerEntry>();
        var ctx = buildContext(config, queued);

        assertThatExceptionOfType(CrawlerException.class)
                .isThrownBy(() -> new RefFileEnqueuer().enqueue(ctx))
                .withMessageContaining("Could not process references file:");
    }

    // -----------------------------------------------------------------
    // Empty config (no files)
    // -----------------------------------------------------------------

    @Test
    void noFilesConfigured_returnsZero() throws Exception {
        var config = new CrawlerConfig();
        config.setStartReferencesFiles(List.of());

        var queued = new ArrayList<CrawlerEntry>();
        var ctx = buildContext(config, queued);

        var result = new RefFileEnqueuer().enqueue(ctx);

        assertThat(result).isZero();
    }
}
