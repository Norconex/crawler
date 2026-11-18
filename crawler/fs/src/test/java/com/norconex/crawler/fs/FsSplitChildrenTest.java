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
package com.norconex.crawler.fs;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.committer.core.impl.MemoryCommitter;
import com.norconex.crawler.core.Crawler;
import com.norconex.importer.handler.splitter.impl.TextChunkSplitter;

/**
 * A file split into chunks that replace it, crawled again and again. The
 * chunks cannot be fetched by their own reference, so what becomes of them
 * follows their parent: when it is unchanged (and so skipped) they stay, when
 * it changes they are replaced, when it goes they go.
 * <pre>
 * data/long.txt   (split in chunks, the original discarded)
 * data/other.txt  (short: left whole)
 * </pre>
 */
@Timeout(240)
class FsSplitChildrenTest {

    private static final String CHUNK = "#chunk";

    @TempDir
    private Path tempDir;

    private Path data;
    private Path work;

    @BeforeEach
    void createTree() throws Exception {
        data = Files.createDirectories(tempDir.resolve("data"));
        work = Files.createDirectories(tempDir.resolve("work"));
        Files.writeString(data.resolve("long.txt"), text(40));
        Files.writeString(data.resolve("other.txt"), "short");
    }

    /** About {@code sentences} sentences, none the same, so no two chunks are. */
    private static String text(int sentences) {
        return IntStream.rangeClosed(1, sentences)
                .mapToObj(i -> "Sentence number " + i
                        + " says something that no other one says.")
                .collect(Collectors.joining(" "));
    }

    /** One crawl session over the same tree and work directory. */
    private MemoryCommitter crawl() throws Exception {
        var mem = new MemoryCommitter();
        var splitter = new TextChunkSplitter();
        splitter.getConfiguration().setMaxChunkSize(300).setChunkOverlap(0);
        var config = new FsCrawlerConfig();
        config.setId("test")
                .setWorkDir(work)
                .setStartReferences(List.of(
                        data.toAbsolutePath().toUri().toString()
                                .replaceAll("/$", "")))
                .setCommitters(List.of(mem));
        config.getImporterConfig().setHandlers(List.of(splitter));
        new Crawler(FsCrawlerDriverFactory.create(), config).crawl();
        return mem;
    }

    private static Set<String> upserted(MemoryCommitter mem) {
        return mem.getUpsertRequests().stream()
                .map(UpsertRequest::getReference)
                .map(FsSplitChildrenTest::name)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    private static Set<String> deleted(MemoryCommitter mem) {
        return mem.getDeleteRequests().stream()
                .map(DeleteRequest::getReference)
                .map(FsSplitChildrenTest::name)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    private static String name(String reference) {
        return reference.substring(reference.lastIndexOf('/') + 1);
    }

    private static List<String> chunksOf(Set<String> names) {
        return names.stream().filter(n -> n.contains(CHUNK)).toList();
    }

    @Test
    void chunksOfAnUnchangedFileStayThroughCrawlAfterCrawl() throws Exception {
        var first = crawl();
        var chunks = chunksOf(upserted(first));
        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(upserted(first)).contains("other.txt");

        // Nothing changed: nothing to send, and above all nothing to delete.
        // The chunks were not seen again, as their parent was skipped, and
        // they cannot be fetched by their own reference.
        for (var run = 2; run <= 4; run++) {
            var again = crawl();
            assertThat(deleted(again)).as("deletions of crawl " + run)
                    .isEmpty();
            assertThat(upserted(again)).as("upserts of crawl " + run).isEmpty();
        }
    }

    @Test
    void aFileThatChangesReplacesItsChunks_andWhatItNoLongerMakesIsDeleted()
            throws Exception {
        var first = chunksOf(upserted(crawl()));
        // A crawl that changes nothing, so the parent is known as unmodified.
        crawl();

        // The file is now much shorter.
        Files.writeString(data.resolve("long.txt"), text(10));
        var changed = crawl();

        // The first chunks are made again (some unchanged, so not sent again),
        // the ones past the new end are not: they are what is deleted, and
        // only that. (The original is also asked to be deleted, as it always
        // was when a rejected document had been seen before: it was never sent.)
        var staleChunks = chunksOf(deleted(changed));
        var kept = first.size() - staleChunks.size();
        assertThat(kept).isPositive();
        assertThat(staleChunks).containsExactlyInAnyOrderElementsOf(
                IntStream.rangeClosed(kept + 1, first.size())
                        .mapToObj(i -> "long.txt" + CHUNK + i)
                        .toList());
        assertThat(deleted(changed)).doesNotContain("other.txt");

        // And the new chunks stay in turn.
        assertThat(deleted(crawl())).isEmpty();
    }

    @Test
    void whenTheFileGoesItsChunksGoWithIt() throws Exception {
        var chunks = chunksOf(upserted(crawl()));
        crawl();

        Files.delete(data.resolve("long.txt"));
        var gone = crawl();

        assertThat(deleted(gone)).containsAll(chunks);
        // Once, and no more.
        assertThat(deleted(crawl())).isEmpty();
    }
}
