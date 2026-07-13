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
package com.norconex.crawler.fs.fetch.impl.egnyte;

import static com.norconex.crawler.core.fetch.FetchDirective.DOCUMENT;
import static com.norconex.crawler.core.fetch.FetchDirective.METADATA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.CrawlerException;
import com.norconex.crawler.core.cluster.CacheMap;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.fetch.FetchException;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.core.session.CrawlerAttributes;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.crawler.fs.fetch.FileFetchResponse;
import com.norconex.crawler.fs.fetch.FolderPathsFetchRequest;
import com.norconex.crawler.fs.fetch.FolderPathsFetchResponse;
import com.norconex.importer.doc.Doc;

@Timeout(30)
class EgnyteFetcherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testAcceptRequest() {
        var f = new StubEgnyteFetcher();

        assertThat(f.accept(new FileFetchRequest(
                new Doc("egnyte://acme/folders/root/items/123"),
                DOCUMENT))).isTrue();
        assertThat(f.accept(new FileFetchRequest(
                new Doc("box://acme/folders/root/items/123"),
                DOCUMENT))).isFalse();
    }

    @Test
    void testFetchFileItem() throws Exception {
        var f = new StubEgnyteFetcher();
        f.item = new EgnyteFetcher.EgnyteItem(objectMapper.readTree("""
                {
                  "id":"123",
                  "type":"file",
                  "name":"sample.txt",
                  "path":"/Shared/sample.txt",
                  "checksum":"abc",
                  "size":42,
                  "created_at":"2026-07-11T10:00:00Z",
                  "last_modified":"2026-07-11T11:00:00Z"
                }
                """), true, false);

        var doc = new Doc("egnyte://acme/folders/root/items/123");
        var response = (FileFetchResponse) f.fetch(
                new FileFetchRequest(doc, DOCUMENT));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(response.isFile()).isTrue();
        assertThat(response.isFolder()).isFalse();
        assertThat(f.lastContentPath).isEqualTo("/fs-ids/123/content");
        assertThat(doc.getInputStream()).isNotNull();
        assertThat(doc.getMetadata().getString("crawler.egnyte.id"))
                .isEqualTo("123");
        assertThat(doc.getMetadata().getString("crawler.egnyte.type"))
                .isEqualTo("file");
    }

    @Test
    void testFetchFileReferenceThatIsNotAnItemReturnsFolder()
            throws Exception {
        var f = new StubEgnyteFetcher();

        var response = (FileFetchResponse) f.fetch(
                new FileFetchRequest(new Doc(
                        "egnyte://acme/folders/root"),
                        DOCUMENT));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(response.isFile()).isFalse();
        assertThat(response.isFolder()).isTrue();
    }

    @Test
    void testFetchFileItemNotFound() throws Exception {
        var f = new StubEgnyteFetcher();
        f.item = null;

        var response = (FileFetchResponse) f.fetch(
                new FileFetchRequest(
                        new Doc("egnyte://acme/folders/root/items/missing"),
                        DOCUMENT));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NOT_FOUND);
    }

    @Test
    void testFetchFileBadStatusAndIOExceptionPaths() throws Exception {
        var badStatus = new StubEgnyteFetcher();
        badStatus.throwItemStatus = true;

        var badStatusResp = (FileFetchResponse) badStatus.fetch(
                new FileFetchRequest(
                        new Doc("egnyte://acme/folders/root/items/123"),
                        DOCUMENT));
        assertThat(badStatusResp.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.BAD_STATUS);

        var io = new StubEgnyteFetcher();
        io.item = new EgnyteFetcher.EgnyteItem(
                objectMapper.readTree("{}"),
                true, false);
        io.throwContentIo = true;
        assertThatThrownBy(() -> io.fetch(new FileFetchRequest(
                new Doc("egnyte://acme/folders/root/items/123"),
                DOCUMENT)))
                        .isInstanceOf(FetchException.class)
                        .hasMessageContaining(
                                "Could not fetch Egnyte reference");
    }

    @Test
    void testFetchFileContentNotFoundDoesNotSetStream() throws Exception {
        var f = new StubEgnyteFetcher();
        f.item = new EgnyteFetcher.EgnyteItem(objectMapper.readTree("""
                {
                  "id":"777",
                  "type":"file",
                  "name":"missing.bin",
                  "path":"/Shared/missing.bin"
                }
                """), true, false);
        f.returnNullContentBytes = true;

        var doc = new Doc("egnyte://acme/folders/root/items/777");
        var response = (FileFetchResponse) f.fetch(
                new FileFetchRequest(doc, DOCUMENT));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(f.lastContentPath).isEqualTo("/fs-ids/777/content");
    }

    @Test
    void testFetchMetadataInvalidDateAndNoDocumentDirective()
            throws Exception {
        var f = new StubEgnyteFetcher();
        f.item = new EgnyteFetcher.EgnyteItem(objectMapper.readTree("""
                {
                  "id":"456",
                  "name":"n.txt",
                  "type":"file",
                  "path":"/Shared/n.txt",
                  "created_at":"not-a-date",
                  "last_modified":"also-bad"
                }
                """), true, false);

        var doc = new Doc("egnyte://acme/folders/root/items/456");
        var response = (FileFetchResponse) f.fetch(
                new FileFetchRequest(doc, METADATA));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(f.lastContentPath).isNull();
        assertThat(doc.getMetadata().getString(
                "crawler.egnyte.invalidDate.crawler.egnyte.created"))
                        .isEqualTo("not-a-date");
        assertThat(doc.getMetadata().getString(
                "crawler.egnyte.invalidDate.crawler.lastModified"))
                        .isEqualTo("also-bad");
    }

    @Test
    void testFetchFolderReturnsChildPathsAcrossPages() throws Exception {
        var f = new StubEgnyteFetcher();
        f.folderPages.add(objectMapper.readTree("""
                {
                  "entries":[
                    {"id":"100","is_folder":false},
                    {"id":"200","is_folder":true}
                  ],
                  "limit":2,
                  "total_count":3
                }
                """));
        f.folderPages.add(objectMapper.readTree("""
                {
                  "entries":[
                    {"id":"300","type":"file"}
                  ],
                  "limit":2,
                  "total_count":3
                }
                """));

        var response = (FolderPathsFetchResponse) f.fetch(
                new FolderPathsFetchRequest(
                        new Doc("egnyte://acme/folders/root")));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(response.getChildPaths())
                .extracting(path -> path.getUri())
                .containsExactlyInAnyOrder(
                        "egnyte://acme/folders/root/items/100",
                        "egnyte://acme/folders/root/items/200",
                        "egnyte://acme/folders/root/items/300");
    }

    @Test
    void testFetchFolderNotFound() throws Exception {
        var f = new StubEgnyteFetcher();
        f.returnNullFolderPage = true;

        var response = (FolderPathsFetchResponse) f.fetch(
                new FolderPathsFetchRequest(
                        new Doc("egnyte://acme/folders/missing")));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NOT_FOUND);
    }

    @Test
    void testFetchFolderBadStatusAndIOExceptionPaths() throws Exception {
        var badStatus = new StubEgnyteFetcher();
        badStatus.throwFolderStatus = true;

        var badStatusResp = (FolderPathsFetchResponse) badStatus.fetch(
                new FolderPathsFetchRequest(
                        new Doc("egnyte://acme/folders/root")));
        assertThat(badStatusResp.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.BAD_STATUS);

        var io = new StubEgnyteFetcher();
        io.throwFolderIo = true;
        assertThatThrownBy(() -> io.fetch(new FolderPathsFetchRequest(
                new Doc("egnyte://acme/folders/root"))))
                        .isInstanceOf(FetchException.class)
                        .hasMessageContaining(
                                "Could not fetch Egnyte child references");
    }

    @Test
    void testFetchFolderUsesAbsoluteChangesAndChildrenUrls()
            throws Exception {
        var f = new StubEgnyteFetcher();
        f.getConfiguration().setApiBaseUrl("https://{domain}/api");

        var method = EgnyteFetcher.class.getDeclaredMethod(
                "buildUri", String.class, String.class);
        method.setAccessible(true);

        assertThat(method.invoke(f, "acme", "/children"))
                .hasToString("https://acme/api/children");
        assertThat(method.invoke(f, "acme",
                "https://example.com/direct"))
                        .hasToString(
                                "https://example.com/direct");
    }

    @Test
    void testSourceDeltaEnabledOnIncrementalStartup() {
        var fetcher = new EgnyteFetcher();

        fetcher.fetcherStartup(mockSession(true,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA,
                "egnyte://acme/folders/root"));

        assertThat(fetcher.isSourceDeltaEnabled()).isTrue();
    }

    @Test
    void testSourceDeltaDisabledWithoutIncrementalSourceDelta() {
        var fetcher = new EgnyteFetcher();
        fetcher.fetcherStartup(mockSession(true,
                CrawlerConfig.ChangeDiscovery.CRAWLER_SCAN,
                "egnyte://acme/folders/root"));
        assertThat(fetcher.isSourceDeltaEnabled()).isFalse();

        fetcher.fetcherStartup(mockSession(false,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA,
                "egnyte://acme/folders/root"));
        assertThat(fetcher.isSourceDeltaEnabled()).isFalse();
    }

    @Test
    void testSourceDeltaRejectsNonRootBoundary() {
        var fetcher = new EgnyteFetcher();

        assertThatThrownBy(() -> fetcher.fetcherStartup(mockSession(
                true,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA,
                "egnyte://acme/folders/team-a")))
                        .isInstanceOf(CrawlerException.class)
                        .hasMessageContaining(
                                "Unsupported Egnyte SOURCE_DELTA")
                        .hasMessageContaining(
                                "root folder");
    }

    @Test
    void testSourceDeltaUsesChangesFeedAndPersistsCursor()
            throws Exception {
        var f = new DeltaEgnyteFetcher();
        f.startCursor = "start-cursor";
        f.changePages.add(objectMapper.readTree("""
                {
                  "events":[
                    {"item_id":"itemA","is_folder":false},
                    {"item_id":"itemB","deleted":true}
                  ],
                  "new_cursor":"next-cursor"
                }
                """));
        var attrs = newCrawlerAttributes();
        f.fetcherStartup(mockSession(true,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA,
                attrs,
                "egnyte://acme/folders/root"));

        var response = (FolderPathsFetchResponse) f.fetch(
                new FolderPathsFetchRequest(
                        new Doc("egnyte://acme/folders/root")));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(response.getChildPaths())
                .extracting(path -> path.getUri())
                .containsExactlyInAnyOrder(
                        "egnyte://acme/folders/root/items/itemA",
                        "egnyte://acme/folders/root/items/itemB");
        assertThat(f.requestedChangeTokens)
                .containsExactly("start-cursor");
        assertThat(attrs.getString(
                "egnyte.delta.cursor.egnyte://acme/folders/root"))
                        .contains("next-cursor");
    }

    @Test
    void testSourceDeltaResetsInvalidStoredCursor() throws Exception {
        var f = new DeltaEgnyteFetcher();
        f.startCursor = "fresh-cursor";
        f.throwInvalidStoredCursor = true;
        f.changePages.add(objectMapper.readTree("""
                {
                  "events":[],
                  "new_cursor":"after-reset"
                }
                """));
        var attrs = newCrawlerAttributes();
        attrs.setString(
                "egnyte.delta.cursor.egnyte://acme/folders/root",
                "stale-cursor");
        f.fetcherStartup(mockSession(true,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA,
                attrs,
                "egnyte://acme/folders/root"));

        var response = (FolderPathsFetchResponse) f.fetch(
                new FolderPathsFetchRequest(
                        new Doc("egnyte://acme/folders/root")));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(f.requestedChangeTokens)
                .containsExactly("stale-cursor",
                        "fresh-cursor");
        assertThat(attrs.getString(
                "egnyte.delta.cursor.egnyte://acme/folders/root"))
                        .contains("after-reset");
    }

    @Test
    void testSourceDeltaReturnsNotFoundWhenChangesPageMissing()
            throws Exception {
        var f = new DeltaEgnyteFetcher();
        f.startCursor = "start-cursor";
        f.returnNullChangePage = true;

        var attrs = newCrawlerAttributes();
        f.fetcherStartup(mockSession(true,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA,
                attrs,
                "egnyte://acme/folders/root"));

        var response = (FolderPathsFetchResponse) f.fetch(
                new FolderPathsFetchRequest(
                        new Doc("egnyte://acme/folders/root")));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NOT_FOUND);
    }

    @Test
    void testFetchStartCursorUsesCursorAndStartCursorFallback()
            throws Exception {
        var f = new DeltaEgnyteFetcher();
        f.startCursorPage = objectMapper.readTree("""
                { "cursor": "   ", "start_cursor": "start-123" }
                """);

        var method = EgnyteFetcher.class.getDeclaredMethod(
                "fetchStartCursor", EgnyteReference.class);
        method.setAccessible(true);
        var result = (String) method.invoke(f,
                EgnyteReference.parse(
                        "egnyte://acme/folders/root"));

        assertThat(result).isEqualTo("start-123");
    }

    private static class StubEgnyteFetcher extends EgnyteFetcher {
        private final Deque<JsonNode> folderPages = new ArrayDeque<>();
        private EgnyteFetcher.EgnyteItem item;
        private String lastContentPath;
        private boolean returnNullFolderPage;
        private boolean throwItemStatus;
        private boolean throwFolderStatus;
        private boolean throwContentIo;
        private boolean throwFolderIo;
        private boolean returnNullContentBytes;
        private String apiBaseUrl;

        @Override
        public EgnyteFetcherConfig getConfiguration() {
            var config = super.getConfiguration();
            if (apiBaseUrl != null) {
                config.setApiBaseUrl(apiBaseUrl);
            }
            return config;
        }

        @Override
        EgnyteFetcher.EgnyteItem
                fetchEgnyteItemNode(EgnyteReference ref)
                        throws IOException {
            if (throwItemStatus) {
                throw new EgnyteFetcher.EgnyteHttpStatusException(
                        500,
                        "bad");
            }
            return item;
        }

        @Override
        JsonNode fetchFolderItemsNode(EgnyteReference ref, int offset)
                throws IOException {
            if (throwFolderStatus) {
                throw new EgnyteFetcher.EgnyteHttpStatusException(
                        500,
                        "bad");
            }
            if (throwFolderIo) {
                throw new IOException("boom");
            }
            if (returnNullFolderPage) {
                return null;
            }
            return folderPages.pollFirst();
        }

        @Override
        byte[] fetchContentBytes(EgnyteReference ref, String path)
                throws IOException {
            if (throwContentIo) {
                throw new IOException("boom");
            }
            lastContentPath = path;
            if (returnNullContentBytes) {
                return null;
            }
            return "payload".getBytes(StandardCharsets.UTF_8);
        }

    }

    private static class DeltaEgnyteFetcher extends StubEgnyteFetcher {
        private String startCursor;
        private boolean throwInvalidStoredCursor;
        private boolean returnNullChangePage;
        private final Deque<JsonNode> changePages = new ArrayDeque<>();
        private final Deque<String> requestedChangeTokens =
                new ArrayDeque<>();
        private JsonNode startCursorPage;

        @Override
        String fetchStartCursor(EgnyteReference ref) {
            if (startCursorPage != null) {
                var cursor = startCursorPage.path("cursor")
                        .asText(null);
                if (cursor != null && !cursor.isBlank()) {
                    return cursor;
                }
                var startCursor =
                        startCursorPage.path(
                                "start_cursor")
                                .asText(null);
                if (startCursor != null
                        && !startCursor.isBlank()) {
                    return startCursor;
                }
                return null;
            }
            return startCursor;
        }

        @Override
        JsonNode fetchChangesNode(EgnyteReference ref, String cursor)
                throws IOException {
            requestedChangeTokens.add(cursor);
            if (returnNullChangePage) {
                returnNullChangePage = false;
                return null;
            }
            if (throwInvalidStoredCursor
                    && "stale-cursor".equals(cursor)) {
                throwInvalidStoredCursor = false;
                throw new EgnyteFetcher.EgnyteHttpStatusException(
                        410,
                        "invalid cursor");
            }
            return changePages.pollFirst();
        }
    }

    private static CrawlerSession mockSession(
            boolean incremental,
            CrawlerConfig.ChangeDiscovery changeDiscovery,
            String... startReferences) {
        return mockSession(incremental, changeDiscovery,
                newCrawlerAttributes(), startReferences);
    }

    private static CrawlerSession mockSession(
            boolean incremental,
            CrawlerConfig.ChangeDiscovery changeDiscovery,
            CrawlerAttributes sessionAttributes,
            String... startReferences) {
        var config = new CrawlerConfig()
                .setChangeDiscovery(changeDiscovery)
                .setStartReferences(List.of(startReferences));
        var context = mock(CrawlerContext.class);
        when(context.getCrawlConfig()).thenReturn(config);

        var session = mock(CrawlerSession.class);
        when(session.getCrawlContext()).thenReturn(context);
        when(session.getSessionAttributes())
                .thenReturn(sessionAttributes);
        when(session.isIncremental()).thenReturn(incremental);
        return session;
    }

    private static CrawlerAttributes newCrawlerAttributes() {
        var values = new java.util.HashMap<String, String>();
        @SuppressWarnings("unchecked")
        CacheMap<String> cache = mock(CacheMap.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            values.put(invocation.getArgument(0),
                    invocation.getArgument(1));
            return null;
        }).when(cache).put(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doAnswer(invocation -> {
            var key = invocation.getArgument(0, String.class);
            var value = invocation.getArgument(1, String.class);
            return values.putIfAbsent(key, value);
        }).when(cache).putIfAbsent(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doAnswer(invocation -> Optional.ofNullable(
                values.get(invocation.getArgument(0,
                        String.class))))
                .when(cache)
                .get(org.mockito.ArgumentMatchers.anyString());
        return new CrawlerAttributes(cache);
    }
}
