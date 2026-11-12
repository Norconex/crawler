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
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.impl.MemoryCommitter;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.crawler.core.Crawler;
import com.norconex.crawler.core.doc.operations.filter.OnMatch;
import com.norconex.crawler.core.doc.operations.filter.impl.GenericReferenceFilter;
import com.norconex.crawler.core.event.listeners.DeleteRejectedEventListener;

/**
 * Deleting what a filter rejects from the committer: by a reference filter
 * (its rejection event names the reference), and, when asked, only what a
 * previous session committed.
 * <pre>
 * data/a.txt
 * data/b.txt
 * data/c.txt  (added after the first crawl)
 * </pre>
 */
@Timeout(240)
class FsDeleteRejectedTest {

    @TempDir
    private Path tempDir;

    private Path data;
    private Path work;

    @BeforeEach
    void createTree() throws Exception {
        data = Files.createDirectories(tempDir.resolve("data"));
        work = Files.createDirectories(tempDir.resolve("work"));
        Files.writeString(data.resolve("a.txt"), "aaa");
        Files.writeString(data.resolve("b.txt"), "bbb");
    }

    /** One crawl session over the same tree and work directory. */
    private MemoryCommitter crawl(
            boolean deleteRejected,
            boolean previouslyCommittedOnly,
            String excludeRegex) throws Exception {
        var mem = new MemoryCommitter();
        var config = new FsCrawlerConfig();
        config.setId("test")
                .setWorkDir(work)
                .setStartReferences(List.of(
                        data.toAbsolutePath().toUri().toString()
                                .replaceAll("/$", "")))
                .setCommitters(List.of(mem));
        if (excludeRegex != null) {
            var filter = new GenericReferenceFilter();
            filter.getConfiguration()
                    .setOnMatch(OnMatch.EXCLUDE)
                    .setValueMatcher(TextMatcher.regex(excludeRegex));
            config.setReferenceFilters(List.of(filter));
        }
        if (deleteRejected) {
            var listener = new DeleteRejectedEventListener();
            listener.getConfiguration()
                    .setEventMatcher(TextMatcher.regex("REJECTED_FILTER"))
                    .setPreviouslyCommittedOnly(previouslyCommittedOnly);
            config.addEventListener(listener);
        }
        new Crawler(FsCrawlerDriverFactory.create(), config).crawl();
        return mem;
    }

    private static Set<String> names(MemoryCommitter mem) {
        return mem.getDeleteRequests().stream()
                .map(DeleteRequest::getReference)
                .map(ref -> ref.substring(ref.lastIndexOf('/') + 1))
                .collect(Collectors.toSet());
    }

    @Test
    void aReferenceFilterRejectionCanDeleteWhatWasNeverCommitted_byDefault()
            throws Exception {
        // The default is to send a deletion for every rejected reference, even
        // on a first crawl and for what was never committed. A reference
        // filter's rejection now names the reference, so it can trigger one.
        var mem = crawl(true, false, ".*/b\\.txt");

        assertThat(names(mem)).containsExactly("b.txt");
    }

    @Test
    void onlyWhatAPreviousSessionCommittedIsDeleted_andOnlyOnce()
            throws Exception {
        // 1. everything is committed
        var first = crawl(false, false, null);
        assertThat(first.getUpsertCount()).isEqualTo(2);

        // a file that no session has committed
        Files.writeString(data.resolve("c.txt"), "ccc");

        // 2. b and c are now left out: only b was ever committed
        var second = crawl(true, true, ".*/[bc]\\.txt");
        assertThat(names(second)).containsExactly("b.txt");

        // 3. the same again: b is gone from the committer, nothing to repeat
        var third = crawl(true, true, ".*/[bc]\\.txt");
        assertThat(names(third)).isEmpty();
    }

    @Test
    void nothingIsDeletedOnAFirstCrawl_whenOnlyWhatWasCommittedIsAsked()
            throws Exception {
        var mem = crawl(true, true, ".*/b\\.txt");

        assertThat(names(mem)).isEmpty();
    }

    @Test
    void whatIsStillReadIsNeverDeleted() throws Exception {
        crawl(false, false, null);
        Files.writeString(data.resolve("c.txt"), "ccc");

        var mem = crawl(true, true, ".*/b\\.txt");

        assertThat(names(mem)).containsExactly("b.txt");
    }
}
