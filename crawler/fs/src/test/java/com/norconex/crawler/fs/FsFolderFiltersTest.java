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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.committer.core.UpsertRequest;
import com.norconex.committer.core.impl.MemoryCommitter;
import com.norconex.commons.lang.Operator;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.crawler.core.Crawler;
import com.norconex.crawler.core.doc.operations.filter.OnMatch;
import com.norconex.crawler.core.doc.operations.filter.impl.ConditionMetadataFilter;
import com.norconex.crawler.core.doc.operations.filter.impl.ExtensionReferenceFilter;
import com.norconex.crawler.core.doc.operations.filter.impl.GenericMetadataFilter;
import com.norconex.crawler.core.doc.operations.filter.impl.GenericReferenceFilter;
import com.norconex.crawler.core.doc.operations.filter.impl.condition.MetadataDateCondition;
import com.norconex.importer.handler.condition.impl.DateValueMatcher;

/**
 * Filters of a file crawler: files are judged by the reference, metadata and
 * document filters, folders (to open or not) by the folder filters, and start
 * references by none.
 * <pre>
 * data/a.txt
 * data/sub/b.txt
 * data/sub/deep/c.txt
 * </pre>
 */
@Timeout(120)
class FsFolderFiltersTest {

    @TempDir
    private Path tempDir;

    private Path data;

    @BeforeEach
    void createTree() throws Exception {
        data = Files.createDirectories(tempDir.resolve("data"));
        Files.writeString(data.resolve("a.txt"), "aaaaa");
        Files.createDirectories(data.resolve("sub/deep"));
        Files.writeString(data.resolve("sub/b.txt"), "bbbbbbbbbbbb");
        Files.writeString(data.resolve("sub/deep/c.txt"), "c");
    }

    /** Crawls the tree and returns what was committed, relative to it. */
    private Set<String> crawl(Consumer<FsCrawlerConfig> configurer)
            throws Exception {
        var mem = new MemoryCommitter();
        var config = new FsCrawlerConfig();
        config.setId("test")
                .setWorkDir(Files.createTempDirectory(tempDir, "work"))
                .setStartReferences(List.of(
                        data.toAbsolutePath().toUri().toString()
                                .replaceAll("/$", "")))
                .setCommitters(List.of(mem));
        configurer.accept(config);
        new Crawler(FsCrawlerDriverFactory.create(), config).crawl();
        return mem.getUpsertRequests().stream()
                .map(UpsertRequest::getReference)
                .map(ref -> ref.substring(ref.indexOf("/data/") + 6))
                .collect(java.util.stream.Collectors.toSet());
    }

    private static GenericReferenceFilter pathFilter(
            OnMatch onMatch, String regex) {
        var filter = new GenericReferenceFilter();
        filter.getConfiguration()
                .setOnMatch(onMatch)
                .setValueMatcher(TextMatcher.regex(regex));
        return filter;
    }

    @Test
    void noFilter_everyFileIsCrawled() throws Exception {
        assertThat(crawl(c -> {})).containsExactlyInAnyOrder("a.txt",
                "sub/b.txt", "sub/deep/c.txt");
    }

    @Test
    void includeFilters_neverRejectFolders() throws Exception {
        // Before: a folder has no extension nor size, so an include rule
        // rejected the start folder and nothing at all was crawled.
        var ext = new ExtensionReferenceFilter();
        ext.getConfiguration().setExtensions(Set.of("txt"))
                .setOnMatch(OnMatch.INCLUDE);
        assertThat(crawl(c -> c.setReferenceFilters(List.of(ext))))
                .containsExactlyInAnyOrder(
                        "a.txt", "sub/b.txt", "sub/deep/c.txt");

        var size = new GenericMetadataFilter();
        size.getConfiguration().setOnMatch(OnMatch.INCLUDE)
                .setFieldMatcher(TextMatcher.basic("crawler.fileSize"))
                .setValueMatcher(TextMatcher.regex("\\d+"));
        assertThat(crawl(c -> c.setMetadataFilters(List.of(size))))
                .containsExactlyInAnyOrder(
                        "a.txt", "sub/b.txt", "sub/deep/c.txt");
    }

    @Test
    void referenceFilters_stillJudgeFiles() throws Exception {
        assertThat(crawl(c -> c.setReferenceFilters(List.of(
                pathFilter(OnMatch.EXCLUDE, ".*/b\\.txt")))))
                        .containsExactlyInAnyOrder("a.txt", "sub/deep/c.txt");
    }

    @Test
    void folderReferenceFilters_excludeKeepsAFolderFromBeingOpened()
            throws Exception {
        assertThat(crawl(c -> c.setFolderReferenceFilters(List.of(
                pathFilter(OnMatch.EXCLUDE, ".*/deep/?")))))
                        .containsExactlyInAnyOrder("a.txt", "sub/b.txt");
        assertThat(crawl(c -> c.setFolderReferenceFilters(List.of(
                pathFilter(OnMatch.EXCLUDE, ".*/sub/?")))))
                        .containsExactlyInAnyOrder("a.txt");
    }

    @Test
    void folderReferenceFilters_includeMeansOnlyOpenThose() throws Exception {
        assertThat(crawl(c -> c.setFolderReferenceFilters(List.of(
                pathFilter(OnMatch.INCLUDE, ".*/nothing/?")))))
                        .containsExactlyInAnyOrder("a.txt");
        assertThat(crawl(c -> c.setFolderReferenceFilters(List.of(
                pathFilter(OnMatch.INCLUDE, ".*/sub(/deep)?/?")))))
                        .containsExactlyInAnyOrder(
                                "a.txt", "sub/b.txt", "sub/deep/c.txt");
    }

    @Test
    void folderReferenceFilters_doNotJudgeFiles() throws Exception {
        // would match every file if files were tested
        assertThat(crawl(c -> c.setFolderReferenceFilters(List.of(
                pathFilter(OnMatch.EXCLUDE, ".*\\.txt")))))
                        .containsExactlyInAnyOrder(
                                "a.txt", "sub/b.txt", "sub/deep/c.txt");
    }

    @Test
    void startReferences_areNeverFiltered() throws Exception {
        // Every folder is refused, except that the start one is not tested
        // (so a.txt, directly in it, is still found).
        assertThat(crawl(c -> c.setFolderReferenceFilters(List.of(
                pathFilter(OnMatch.EXCLUDE, ".*")))))
                        .containsExactlyInAnyOrder("a.txt");
        // Nor are the filters of files applied to a start reference that
        // is a file.
        assertThat(crawl(c -> {
            c.setStartReferences(List.of(
                    data.resolve("a.txt").toAbsolutePath().toUri()
                            .toString()));
            c.setReferenceFilters(List.of(
                    pathFilter(OnMatch.EXCLUDE, ".*\\.txt")));
        })).hasSize(1);
    }

    @Test
    void folderMetadataFilters_canJudgeFolderDates() throws Exception {
        // every folder was last modified before the year 2999
        var old = new ConditionMetadataFilter();
        old.getConfiguration().setOnMatch(OnMatch.EXCLUDE)
                .setCondition(new MetadataDateCondition()
                        .setFieldMatcher(
                                TextMatcher.basic("crawler.lastModified"))
                        .setValueMatcher(new DateValueMatcher(
                                Operator.LOWER_THAN, "2999-01-01", null)));
        assertThat(crawl(c -> c.setFolderMetadataFilters(List.of(old))))
                .containsExactlyInAnyOrder("a.txt");

        // none was last modified before the year 2000
        var ancient = new ConditionMetadataFilter();
        ancient.getConfiguration().setOnMatch(OnMatch.EXCLUDE)
                .setCondition(new MetadataDateCondition()
                        .setFieldMatcher(
                                TextMatcher.basic("crawler.lastModified"))
                        .setValueMatcher(new DateValueMatcher(
                                Operator.LOWER_THAN, "2000-01-01", null)));
        assertThat(crawl(c -> c.setFolderMetadataFilters(List.of(ancient))))
                .containsExactlyInAnyOrder(
                        "a.txt", "sub/b.txt", "sub/deep/c.txt");
    }

    @Test
    void folderMetadataFilters_canJudgeHiddenFolders() throws Exception {
        // a folder has the "hidden" field, as a file does
        var hidden = new GenericMetadataFilter();
        hidden.getConfiguration().setOnMatch(OnMatch.EXCLUDE)
                .setFieldMatcher(TextMatcher.basic("crawler.hidden"))
                .setValueMatcher(TextMatcher.basic("true"));
        assertThat(crawl(c -> c.setFolderMetadataFilters(List.of(hidden))))
                .containsExactlyInAnyOrder(
                        "a.txt", "sub/b.txt", "sub/deep/c.txt");

        var visible = new GenericMetadataFilter();
        visible.getConfiguration().setOnMatch(OnMatch.EXCLUDE)
                .setFieldMatcher(TextMatcher.basic("crawler.hidden"))
                .setValueMatcher(TextMatcher.basic("false"));
        assertThat(crawl(c -> c.setFolderMetadataFilters(List.of(visible))))
                .containsExactlyInAnyOrder("a.txt");
    }

    @Test
    void folderMetadataFilters_doNotJudgeFiles() throws Exception {
        assertThat(crawl(c -> c.setFolderMetadataFilters(List.of(
                new ConditionMetadataFilter() {
                    {
                        getConfiguration().setOnMatch(OnMatch.EXCLUDE)
                                .setCondition(new MetadataDateCondition()
                                        .setFieldMatcher(TextMatcher.basic(
                                                "crawler.lastModified"))
                                        .setValueMatcher(new DateValueMatcher(
                                                Operator.LOWER_THAN,
                                                "2999-01-01", null)));
                    }
                }))))
                        // folders refused, files of the start folder kept
                        .containsExactlyInAnyOrder("a.txt");
    }
}
