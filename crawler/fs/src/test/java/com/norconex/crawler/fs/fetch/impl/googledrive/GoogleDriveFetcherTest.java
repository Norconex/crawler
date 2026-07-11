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
package com.norconex.crawler.fs.fetch.impl.googledrive;

import static com.norconex.crawler.core.fetch.FetchDirective.DOCUMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
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
import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.CrawlerException;
import com.norconex.crawler.core.cluster.CacheMap;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.core.session.CrawlerAttributes;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.crawler.fs.fetch.FileFetchResponse;
import com.norconex.crawler.fs.fetch.FolderPathsFetchRequest;
import com.norconex.crawler.fs.fetch.FolderPathsFetchResponse;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.doc.DocMetaConstants;

@Timeout(30)
class GoogleDriveFetcherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testAcceptRequest() {
        var f = new GoogleDriveFetcher();
        assertThat(f.acceptRequest(new FileFetchRequest(
                new Doc("gdrive://workspace-01/drives/drive123/items/item123"),
                DOCUMENT))).isTrue();
        assertThat(f.acceptRequest(new FileFetchRequest(
                new Doc("m365od://tenant/users/user123"),
                DOCUMENT))).isFalse();
    }

    @Test
    void testGoogleDocUsesExportPath() throws Exception {
        var f = new CapturingGoogleDriveFetcher();
        var ref = GoogleDriveReference.parse(
                "gdrive://workspace-01/drives/drive123/items/item123");
        var item = objectMapper.readTree(
                "{\"mimeType\":\"application/vnd.google-apps.document\"}");
        var doc = new Doc(ref.toReference());

        f.fetchContent(doc, ref, item);

        assertThat(f.lastContentPath)
                .isEqualTo("/files/item123/export?mimeType=text%2Fplain");
        assertThat(doc.getInputStream()).isNotNull();
    }

    @Test
    void testBinaryFileUsesAltMediaPath() throws Exception {
        var f = new CapturingGoogleDriveFetcher();
        var ref = GoogleDriveReference.parse(
                "gdrive://workspace-01/drives/drive123/items/item123");
        var item = objectMapper.readTree("{\"mimeType\":\"application/pdf\"}");
        var doc = new Doc(ref.toReference());

        f.fetchContent(doc, ref, item);

        assertThat(f.lastContentPath).isEqualTo("/files/item123?alt=media");
    }

    @Test
    void testUnsupportedGoogleNativeMimeSetsMetadataSignal() throws Exception {
        var f = new CapturingGoogleDriveFetcher();
        f.getConfiguration().setNativeDocumentFormatPolicy(
                GoogleDriveFetcherConfig.NativeDocumentFormatPolicy.PDF);
        f.getConfiguration().getExportMimeTypeMap().put(
                "application/vnd.google-apps.document", "");
        var ref = GoogleDriveReference.parse(
                "gdrive://workspace-01/drives/drive123/items/item123");
        var item = objectMapper.readTree(
                "{\"mimeType\":\"application/vnd.google-apps.document\"}");
        var doc = new Doc(ref.toReference());

        f.fetchContent(doc, ref, item);

        assertThat(f.lastContentPath).isNull();
        assertThat(doc.getMetadata().getString(
                "crawler.gdrive.content.status"))
                        .isEqualTo("unsupported-google-native-mime");
        assertThat(doc.getMetadata().getString(
                "crawler.gdrive.content.sourceMimeType"))
                        .isEqualTo("application/vnd.google-apps.document");
    }

    @Test
    void testOoxmlPolicyUsesOfficeExportPath() throws Exception {
        var f = new CapturingGoogleDriveFetcher();
        f.getConfiguration().setNativeDocumentFormatPolicy(
                GoogleDriveFetcherConfig.NativeDocumentFormatPolicy.OOXML);
        var ref = GoogleDriveReference.parse(
                "gdrive://workspace-01/drives/drive123/items/item123");
        var item = objectMapper.readTree(
                "{\"mimeType\":\"application/vnd.google-apps.document\"}");
        var doc = new Doc(ref.toReference());

        f.fetchContent(doc, ref, item);

        assertThat(f.lastContentPath).isEqualTo(
                "/files/item123/export?mimeType=application%2Fvnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Test
    void testFetchFileSetsExportedContentTypeAndMetadata() throws Exception {
        var item = objectMapper.readTree("""
                {
                  "id":"item123",
                  "name":"Spec Doc",
                  "mimeType":"application/vnd.google-apps.document",
                  "modifiedTime":"2026-07-10T10:15:30Z",
                  "createdTime":"2026-07-09T08:00:00Z",
                  "parents":["parentA"]
                }
                """);
        var f = new StubGoogleDriveFetcher();
        f.itemNode = item;
        var doc =
                new Doc("gdrive://workspace-01/drives/drive123/items/item123");

        var response = (FileFetchResponse) f.fetch(
                new FileFetchRequest(doc, DOCUMENT));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(response.isFile()).isTrue();
        assertThat(response.isFolder()).isFalse();
        assertThat(f.lastContentPath)
                .isEqualTo("/files/item123/export?mimeType=text%2Fplain");
        assertThat(doc.getInputStream()).isNotNull();
        assertThat(doc.getMetadata().getString(DocMetaConstants.CONTENT_TYPE))
                .isEqualTo("text/plain");
        assertThat(doc.getMetadata().getString("crawler.gdrive.mimeType"))
                .isEqualTo("application/vnd.google-apps.document");
        assertThat(doc.getMetadata().getString(
                "crawler.gdrive.content.exportMimeType"))
                        .isEqualTo("text/plain");
        assertThat(doc.getMetadata().getString(
                "crawler.gdrive.content.status"))
                        .isEqualTo("exported");
        assertThat(doc.getMetadata().getString(
                "crawler.gdrive.sourceMimeType"))
                        .isEqualTo("application/vnd.google-apps.document");
    }

    @Test
    void testFetchChildPathsAggregatesPages() throws Exception {
        var f = new StubGoogleDriveFetcher();
        f.childNodes.add(objectMapper.readTree("""
                {
                  "files":[{"id":"itemA"}],
                  "nextPageToken":"page2"
                }
                """));
        f.childNodes.add(objectMapper.readTree("""
                {
                  "files":[{"id":"itemB"}]
                }
                """));

        var response = (FolderPathsFetchResponse) f.fetch(
                new FolderPathsFetchRequest(
                        new Doc("gdrive://workspace-01/drives/drive123")));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(response.getChildPaths())
                .extracting(path -> path.getUri())
                .containsExactly(
                        "gdrive://workspace-01/drives/drive123/items/itemA",
                        "gdrive://workspace-01/drives/drive123/items/itemB");
    }

    @Test
    void testConfigWriteRead() {
        var cfg = new GoogleDriveFetcherConfig()
                .setApplicationName("test-app")
                .setClientEmail("svc@example.com")
                .setDelegatedUser("user@example.com")
                .setPrivateKey(
                        "-----BEGIN PRIVATE KEY-----\nQUJD\n-----END PRIVATE KEY-----");
        cfg.setNativeDocumentFormatPolicy(
                GoogleDriveFetcherConfig.NativeDocumentFormatPolicy.OOXML);
        cfg.getExportMimeTypeMap().put(
                "application/vnd.google-apps.drawing",
                "image/png");
        assertThatNoException().isThrownBy(
                () -> BeanMapper.DEFAULT.assertWriteRead(cfg));
    }

    @Test
    void testSourceDeltaEnabledOnIncrementalStartup() {
        var fetcher = new GoogleDriveFetcher();

        fetcher.fetcherStartup(mockSession(true,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA,
                "gdrive://workspace-01/drives/drive123"));

        assertThat(fetcher.isSourceDeltaEnabled()).isTrue();
    }

    @Test
    void testSourceDeltaDisabledWithoutIncrementalSourceDelta() {
        var fetcher = new GoogleDriveFetcher();
        fetcher.fetcherStartup(mockSession(true,
                CrawlerConfig.ChangeDiscovery.CRAWLER_SCAN,
                "gdrive://workspace-01/drives/drive123"));
        assertThat(fetcher.isSourceDeltaEnabled()).isFalse();

        fetcher.fetcherStartup(mockSession(false,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA,
                "gdrive://workspace-01/drives/drive123"));
        assertThat(fetcher.isSourceDeltaEnabled()).isFalse();
    }

    @Test
    void testSourceDeltaRejectsItemBoundary() {
        var fetcher = new GoogleDriveFetcher();

        assertThatThrownBy(() -> fetcher.fetcherStartup(mockSession(true,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA,
                "gdrive://workspace-01/drives/drive123/items/item123")))
                        .isInstanceOf(CrawlerException.class)
                        .hasMessageContaining(
                                "Unsupported Google Drive SOURCE_DELTA")
                        .hasMessageContaining(
                                "user or shared-drive start reference");
    }

    @Test
    void testSourceDeltaUsesChangesFeedAndPersistsCursor() throws Exception {
        var f = new DeltaGoogleDriveFetcher();
        f.startPageToken = "start-token";
        f.changePages.add(objectMapper.readTree("""
                {
                  "changes":[
                    {"fileId":"itemA","removed":false,
                     "file":{"id":"itemA","mimeType":"application/pdf"}},
                    {"fileId":"itemB","removed":true}
                  ],
                  "newStartPageToken":"next-token"
                }
                """));
        var attrs = newCrawlerAttributes();
        f.fetcherStartup(mockSession(true,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA,
                attrs,
                "gdrive://workspace-01/drives/drive123"));

        var response = (FolderPathsFetchResponse) f.fetch(
                new FolderPathsFetchRequest(
                        new Doc("gdrive://workspace-01/drives/drive123")));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(response.getChildPaths())
                .extracting(path -> path.getUri())
                .containsExactly(
                        "gdrive://workspace-01/drives/drive123/items/itemA",
                        "gdrive://workspace-01/drives/drive123/items/itemB");
        assertThat(f.requestedChangeTokens).containsExactly("start-token");
        assertThat(attrs.getString(
                "googledrive.delta.cursor.gdrive://workspace-01/drives/drive123"))
                        .contains("next-token");
    }

    @Test
    void testSourceDeltaResetsInvalidStoredCursor() throws Exception {
        var f = new DeltaGoogleDriveFetcher();
        f.startPageToken = "fresh-token";
        f.throwInvalidStoredCursor = true;
        f.changePages.add(objectMapper.readTree("""
                {
                  "changes":[],
                  "newStartPageToken":"after-reset"
                }
                """));
        var attrs = newCrawlerAttributes();
        attrs.setString(
                "googledrive.delta.cursor.gdrive://workspace-01/drives/drive123",
                "stale-token");
        f.fetcherStartup(mockSession(true,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA,
                attrs,
                "gdrive://workspace-01/drives/drive123"));

        var response = (FolderPathsFetchResponse) f.fetch(
                new FolderPathsFetchRequest(
                        new Doc("gdrive://workspace-01/drives/drive123")));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(f.requestedChangeTokens)
                .containsExactly("stale-token", "fresh-token");
        assertThat(attrs.getString(
                "googledrive.delta.cursor.gdrive://workspace-01/drives/drive123"))
                        .contains("after-reset");
    }

    private static class CapturingGoogleDriveFetcher
            extends GoogleDriveFetcher {
        protected String lastContentPath;

        @Override
        byte[] fetchContentBytes(String pathOrUrl) throws IOException {
            lastContentPath = pathOrUrl;
            return "payload".getBytes(StandardCharsets.UTF_8);
        }
    }

    private static class StubGoogleDriveFetcher
            extends CapturingGoogleDriveFetcher {
        private JsonNode itemNode;
        private final Deque<JsonNode> childNodes = new ArrayDeque<>();

        @Override
        JsonNode fetchItemNode(GoogleDriveReference ref) {
            return itemNode;
        }

        @Override
        JsonNode fetchChildrenNode(GoogleDriveReference ref, String pageToken) {
            return childNodes.pollFirst();
        }
    }

    private static class DeltaGoogleDriveFetcher
            extends StubGoogleDriveFetcher {
        private String startPageToken;
        private boolean throwInvalidStoredCursor;
        private final Deque<JsonNode> changePages = new ArrayDeque<>();
        private final Deque<String> requestedChangeTokens = new ArrayDeque<>();

        @Override
        String fetchStartPageToken(GoogleDriveReference ref) {
            return startPageToken;
        }

        @Override
        JsonNode fetchChangesNode(GoogleDriveReference ref, String pageToken)
                throws IOException {
            requestedChangeTokens.add(pageToken);
            if (throwInvalidStoredCursor
                    && "stale-token".equals(pageToken)) {
                throwInvalidStoredCursor = false;
                throw new GoogleDriveFetcher.GoogleDriveHttpStatusException(
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
        when(session.getSessionAttributes()).thenReturn(sessionAttributes);
        when(session.isIncremental()).thenReturn(incremental);
        return session;
    }

    private static CrawlerAttributes newCrawlerAttributes() {
        var values = new java.util.HashMap<String, String>();
        @SuppressWarnings("unchecked")
        CacheMap<String> cache = mock(CacheMap.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            values.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(cache).put(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doAnswer(invocation -> {
            var key = invocation.getArgument(0, String.class);
            var value = invocation.getArgument(1, String.class);
            return values.putIfAbsent(key, value);
        }).when(cache).putIfAbsent(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doAnswer(invocation -> Optional.ofNullable(
                values.get(invocation.getArgument(0, String.class))))
                .when(cache).get(org.mockito.ArgumentMatchers.anyString());
        return new CrawlerAttributes(cache);
    }
}
