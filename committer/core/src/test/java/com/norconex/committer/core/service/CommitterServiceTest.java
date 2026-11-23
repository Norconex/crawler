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
package com.norconex.committer.core.service;

import static java.io.InputStream.nullInputStream;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.converter.ConvertWith;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Timeout;

import com.norconex.committer.core.CommitterContext;
import com.norconex.committer.core.CommitterEvent;
import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.StringListConverter;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.committer.core.fs.AbstractFsCommitter;
import com.norconex.committer.core.fs.impl.JsonFileCommitter;
import com.norconex.committer.core.fs.impl.XmlFileCommitter;
import com.norconex.committer.core.impl.MemoryCommitter;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.map.PropertyMatcher;
import com.norconex.commons.lang.text.TextMatcher;

import lombok.Data;

@Timeout(30)
class CommitterServiceTest {

    private final Map<String, TestContext> testContexes = new HashMap<>();

    @Data
    private static class TestContext {
        private final List<MemoryCommitter> committers;
        private final CommitterService<TestDoc> service;
        private final List<String> events = new ArrayList<>();
        private CommitterContext committerContext;

        public TestContext(CommitterService<TestDoc> service) {
            this.service = service;
            committers = service.getCommitters().stream()
                    .map(MemoryCommitter.class::cast)
                    .toList();
            service.getEventManager().addListener(e -> events.add(e.getName()));
            committerContext = CommitterContext.builder()
                    .setEventManager(service.getEventManager())
                    .build();
        }

        void initService() {
            service.init(committerContext);
            events.clear();
        }

        int upsertCount() {
            return committers.stream()
                    .mapToInt(MemoryCommitter::getUpsertCount)
                    .sum();
        }

        int deleteCount() {
            return committers.stream()
                    .mapToInt(MemoryCommitter::getDeleteCount)
                    .sum();
        }

        int closeCount() {
            return (int) committers.stream()
                    .filter(MemoryCommitter::isClosed)
                    .count();
        }
    }

    @Data
    static class TestDoc {
        private String ref;
        private final Properties meta = new Properties();

        public TestDoc(String ref) {
            this.ref = ref;
            meta.set("document.reference", ref);
        }
    }

    @BeforeEach
    void beforeEach() {
        // Case 1:
        var acceptOnlyAAA = new MemoryCommitter();
        acceptOnlyAAA.getConfiguration().addRestriction(
                new PropertyMatcher(
                        TextMatcher.basic("document.reference"),
                        TextMatcher.basic("aaa")));
        testContexes.put(
                "2committers", new TestContext(
                        CommitterService.<TestDoc>builder()
                                .committers(
                                        List.of(
                                                new MemoryCommitter(),
                                                acceptOnlyAAA))
                                .upsertRequestBuilder(
                                        doc -> new UpsertRequest(
                                                doc.getRef(), doc.getMeta(),
                                                null))
                                .deleteRequestBuilder(
                                        doc -> new DeleteRequest(
                                                doc.ref, doc.meta))
                                .build()));

        testContexes.put(
                "0committers", new TestContext(
                        CommitterService.<TestDoc>builder()
                                .committers(List.of())
                                .upsertRequestBuilder(
                                        doc -> new UpsertRequest(
                                                doc.getRef(), doc.getMeta(),
                                                null))
                                .deleteRequestBuilder(
                                        doc -> new DeleteRequest(
                                                doc.ref, doc.meta))
                                .build()));
    }

    @ParameterizedTest
    @CsvSource(
        {
                "2committers, true",
                "0committers, false"
        }
    )
    void testIsOperative(String testCtx, boolean expected) {
        var test = testContexes.get(testCtx);
        assertThat(test.service.isOperative()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(
        {
                "2committers, "
                        + CommitterServiceEvent.COMMITTER_SERVICE_INIT_BEGIN
                        + "|" + CommitterEvent.COMMITTER_INIT_BEGIN
                        + "|" + CommitterEvent.COMMITTER_INIT_END
                        + "|" + CommitterEvent.COMMITTER_INIT_BEGIN
                        + "|" + CommitterEvent.COMMITTER_INIT_END
                        + "|"
                        + CommitterServiceEvent.COMMITTER_SERVICE_INIT_END,
                "0committers, "
                        + CommitterServiceEvent.COMMITTER_SERVICE_INIT_BEGIN
                        + "|"
                        + CommitterServiceEvent.COMMITTER_SERVICE_INIT_END,
        }
    )
    void testInit(
            String testCtx,
            @ConvertWith(StringListConverter.class) String[] expectedEvents) {
        var test = testContexes.get(testCtx);
        test.service.init(test.committerContext);
        assertThat(test.events).containsExactly(expectedEvents);
    }

    @ParameterizedTest
    @CsvSource(
        {
                // testContext, reference, expectedUpsertCount, expectedEvents
                "2committers, aaa, 2, "
                        + CommitterServiceEvent.COMMITTER_SERVICE_UPSERT_BEGIN
                        + "|" + CommitterEvent.COMMITTER_ACCEPT_YES
                        + "|" + CommitterEvent.COMMITTER_UPSERT_BEGIN
                        + "|" + CommitterEvent.COMMITTER_UPSERT_END
                        + "|" + CommitterEvent.COMMITTER_ACCEPT_YES
                        + "|" + CommitterEvent.COMMITTER_UPSERT_BEGIN
                        + "|" + CommitterEvent.COMMITTER_UPSERT_END
                        + "|"
                        + CommitterServiceEvent.COMMITTER_SERVICE_UPSERT_END,
                "0committers, aaa, 0, "
                        + CommitterServiceEvent.COMMITTER_SERVICE_UPSERT_BEGIN
                        + "|"
                        + CommitterServiceEvent.COMMITTER_SERVICE_UPSERT_END,
                "2committers, bbb, 1, "
                        + CommitterServiceEvent.COMMITTER_SERVICE_UPSERT_BEGIN
                        + "|" + CommitterEvent.COMMITTER_ACCEPT_YES
                        + "|" + CommitterEvent.COMMITTER_UPSERT_BEGIN
                        + "|" + CommitterEvent.COMMITTER_UPSERT_END
                        + "|" + CommitterEvent.COMMITTER_ACCEPT_NO
                        + "|"
                        + CommitterServiceEvent.COMMITTER_SERVICE_UPSERT_END,
        }
    )
    void testUpsert(
            String testCtx,
            String reference,
            int expectedUpsertCount,
            @ConvertWith(StringListConverter.class) String[] expectedEvents)
            throws CommitterException {

        var test = testContexes.get(testCtx);
        test.initService();
        test.service.upsert(new TestDoc(reference));
        if (expectedEvents != null) {
            assertThat(test.events).containsExactly(expectedEvents);
        }
        assertThat(test.upsertCount()).isEqualTo(expectedUpsertCount);
    }

    @ParameterizedTest
    @CsvSource(
        {
                // testContext, reference, expectedDeleteCount, expectedEvents
                "2committers, aaa, 2, "
                        + CommitterServiceEvent.COMMITTER_SERVICE_DELETE_BEGIN
                        + "|" + CommitterEvent.COMMITTER_ACCEPT_YES
                        + "|" + CommitterEvent.COMMITTER_DELETE_BEGIN
                        + "|" + CommitterEvent.COMMITTER_DELETE_END
                        + "|" + CommitterEvent.COMMITTER_ACCEPT_YES
                        + "|" + CommitterEvent.COMMITTER_DELETE_BEGIN
                        + "|" + CommitterEvent.COMMITTER_DELETE_END
                        + "|"
                        + CommitterServiceEvent.COMMITTER_SERVICE_DELETE_END,
                "0committers, aaa, 0, "
                        + CommitterServiceEvent.COMMITTER_SERVICE_DELETE_BEGIN
                        + "|"
                        + CommitterServiceEvent.COMMITTER_SERVICE_DELETE_END,
                "2committers, bbb, 1, "
                        + CommitterServiceEvent.COMMITTER_SERVICE_DELETE_BEGIN
                        + "|" + CommitterEvent.COMMITTER_ACCEPT_YES
                        + "|" + CommitterEvent.COMMITTER_DELETE_BEGIN
                        + "|" + CommitterEvent.COMMITTER_DELETE_END
                        + "|" + CommitterEvent.COMMITTER_ACCEPT_NO
                        + "|"
                        + CommitterServiceEvent.COMMITTER_SERVICE_DELETE_END,
        }
    )
    void testDelete(
            String testCtx,
            String reference,
            int expectedDeleteCount,
            @ConvertWith(StringListConverter.class) String[] expectedEvents)
            throws CommitterException {
        var test = testContexes.get(testCtx);
        test.initService();
        test.service.delete(new TestDoc(reference));
        if (expectedEvents != null) {
            assertThat(test.events).containsExactly(expectedEvents);
        }
        assertThat(test.deleteCount()).isEqualTo(expectedDeleteCount);
    }

    @ParameterizedTest
    @CsvSource(
        {
                "2committers, 2, "
                        + CommitterServiceEvent.COMMITTER_SERVICE_CLOSE_BEGIN
                        + "|" + CommitterEvent.COMMITTER_CLOSE_BEGIN
                        + "|" + CommitterEvent.COMMITTER_CLOSE_END
                        + "|" + CommitterEvent.COMMITTER_CLOSE_BEGIN
                        + "|" + CommitterEvent.COMMITTER_CLOSE_END
                        + "|"
                        + CommitterServiceEvent.COMMITTER_SERVICE_CLOSE_END,
                "0committers, 0, "
                        + CommitterServiceEvent.COMMITTER_SERVICE_CLOSE_BEGIN
                        + "|"
                        + CommitterServiceEvent.COMMITTER_SERVICE_CLOSE_END,
        }
    )
    void testClose(
            String testCtx,
            int expectedCloseCount,
            @ConvertWith(StringListConverter.class) String[] expectedEvents) {
        var test = testContexes.get(testCtx);
        test.initService();
        test.service.close();
        assertThat(test.closeCount()).isEqualTo(expectedCloseCount);
        assertThat(test.events).containsExactly(expectedEvents);
    }

    @ParameterizedTest
    @CsvSource(
        {
                "2committers, "
                        + CommitterServiceEvent.COMMITTER_SERVICE_CLEAN_BEGIN
                        + "|" + CommitterEvent.COMMITTER_CLEAN_BEGIN
                        + "|" + CommitterEvent.COMMITTER_CLEAN_END
                        + "|" + CommitterEvent.COMMITTER_CLEAN_BEGIN
                        + "|" + CommitterEvent.COMMITTER_CLEAN_END
                        + "|"
                        + CommitterServiceEvent.COMMITTER_SERVICE_CLEAN_END,
                "0committers, "
                        + CommitterServiceEvent.COMMITTER_SERVICE_CLEAN_BEGIN
                        + "|"
                        + CommitterServiceEvent.COMMITTER_SERVICE_CLEAN_END,
        }
    )
    void testClean(
            String testCtx,
            @ConvertWith(StringListConverter.class) String[] expectedEvents) {
        var test = testContexes.get(testCtx);
        test.initService();
        test.service.clean();
        assertThat(test.events).containsExactly(expectedEvents);
    }

    @ParameterizedTest
    @CsvSource(
        {
                "2committers, 2",
                "0committers, 0"
        }
    )
    void testGetCommitters(String testCtx, int expectedCommitterCount) {
        var test = testContexes.get(testCtx);
        assertThat(test.getService().getCommitters()).hasSize(
                expectedCommitterCount);
    }

    @ParameterizedTest
    @CsvSource(
        {
                "2committers, 'CommitterService[MemoryCommitter,MemoryCommitter]'",
                "0committers, CommitterService[]"
        }
    )
    void testToString(String testCtx, String expectedToString) {
        var test = testContexes.get(testCtx);
        assertThat(test.getService()).hasToString(expectedToString);
    }

    @Test
    void testCommitterDefaultDirs(@TempDir Path tempDir) {
        // Directory names bearing the committer class names should be created.
        // In case of using the same Committer more than once, a counter is
        // added.  It should still be possible to overwrite and give
        // a specific path.

        var overwrittenPath = new XmlFileCommitter();
        overwrittenPath.getConfiguration().setDirectory(
                tempDir.resolve("customOne"));

        @SuppressWarnings("resource")
        var service = CommitterService.builder()
                .committers(
                        List.of(
                                new XmlFileCommitter(),
                                new JsonFileCommitter(),
                                overwrittenPath,
                                new XmlFileCommitter()))
                .upsertRequestBuilder(
                        obj -> new UpsertRequest(
                                "mock", new Properties(), nullInputStream()))
                .deleteRequestBuilder(
                        obj -> new DeleteRequest(
                                "mock", new Properties()))
                .build();

        service.init(
                CommitterContext.builder()
                        .setWorkDir(tempDir)
                        .build());

        var actualDirNames = service.getCommitters().stream()
                .map(AbstractFsCommitter.class::cast)
                .map(c -> c.getResolvedDirectory().getFileName().toString())
                .toList();
        assertThat(actualDirNames).containsExactly(
                "XmlFileCommitter",
                "JsonFileCommitter",
                "customOne",
                // we expect this one to be _3, not _2, since it is the 3rd XML
                // committer, after the "customOne".
                "XmlFileCommitter_3");
    }

    @Test
    void testSendConditionSkipsObjectsItRefuses() {
        var committer = new MemoryCommitter();
        var service = CommitterService.<TestDoc>builder()
                .committers(List.of(committer))
                .upsertRequestBuilder(doc -> new UpsertRequest(
                        doc.getRef(), doc.getMeta(), nullInputStream()))
                .deleteRequestBuilder(doc -> new DeleteRequest(
                        doc.getRef(), doc.getMeta()))
                .sendCondition(doc -> !"refused".equals(doc.getRef()))
                .build();
        var ctx = new TestContext(service);
        ctx.initService();

        assertThat(service.upsert(new TestDoc("refused"))).isEmpty();
        assertThat(service.delete(new TestDoc("refused"))).isEmpty();
        assertThat(ctx.getEvents()).isEmpty();
        assertThat(service.upsert(new TestDoc("sent"))).hasSize(1);
        assertThat(service.delete(new TestDoc("sent"))).hasSize(1);

        assertThat(ctx.upsertCount()).isEqualTo(1);
        assertThat(ctx.deleteCount()).isEqualTo(1);
    }
}
