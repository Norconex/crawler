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
import static com.norconex.crawler.core.fetch.FetchDirective.METADATA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import com.norconex.crawler.fs.doc.FsDocMetadata;
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
        var item = objectMapper
                .readTree("{\"mimeType\":\"application/pdf\"}");
        var doc = new Doc(ref.toReference());

        f.fetchContent(doc, ref, item);

        assertThat(f.lastContentPath)
                .isEqualTo("/files/item123?alt=media");
    }

    @Test
    void testBlankMimeUsesAltMediaPath() throws Exception {
        var f = new CapturingGoogleDriveFetcher();
        var ref = GoogleDriveReference.parse(
                "gdrive://workspace-01/drives/drive123/items/item123");
        var item = objectMapper.readTree("{}");
        var doc = new Doc(ref.toReference());

        f.fetchContent(doc, ref, item);

        assertThat(f.lastContentPath)
                .isEqualTo("/files/item123?alt=media");
    }

    @Test
    void testUnsupportedGoogleNativeMimeSetsMetadataSignal()
            throws Exception {
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
    void testFetchFileSetsExportedContentTypeAndMetadata()
            throws Exception {
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
        assertThat(doc.getMetadata()
                .getString(DocMetaConstants.CONTENT_TYPE))
                        .isEqualTo("text/plain");
        assertThat(doc.getMetadata()
                .getString("crawler.gdrive.mimeType"))
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
    void testFetchFileNotFoundWhenItemMissing() throws Exception {
        var f = new StubGoogleDriveFetcher();
        var doc =
                new Doc("gdrive://workspace-01/drives/drive123/items/item123");

        var response = (FileFetchResponse) f.fetch(
                new FileFetchRequest(doc, DOCUMENT));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NOT_FOUND);
    }

    @Test
    void testFetchFileBadStatusOnApiError() throws Exception {
        var f = new StubGoogleDriveFetcher() {
            @Override
            JsonNode fetchItemNode(GoogleDriveReference ref)
                    throws IOException {
                throw new GoogleDriveFetcher.GoogleDriveHttpStatusException(
                        503,
                        "read file failed");
            }
        };
        var doc =
                new Doc("gdrive://workspace-01/drives/drive123/items/item123");

        var response = (FileFetchResponse) f.fetch(
                new FileFetchRequest(doc, DOCUMENT));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.BAD_STATUS);
    }

    @Test
    void testFetchChildPathsNotFoundWhenApiReturnsNull() throws Exception {
        var f = new StubGoogleDriveFetcher();

        var response = (FolderPathsFetchResponse) f.fetch(
                new FolderPathsFetchRequest(
                        new Doc("gdrive://workspace-01/drives/drive123")));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NOT_FOUND);
    }

    @Test
    void testFetchChildPathsBadStatusOnApiError() throws Exception {
        var f = new StubGoogleDriveFetcher() {
            @Override
            JsonNode fetchChildrenNode(
                    GoogleDriveReference ref,
                    String pageToken)
                    throws IOException {
                throw new GoogleDriveFetcher.GoogleDriveHttpStatusException(
                        429,
                        "read children failed");
            }
        };

        var response = (FolderPathsFetchResponse) f.fetch(
                new FolderPathsFetchRequest(
                        new Doc("gdrive://workspace-01/drives/drive123")));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.BAD_STATUS);
    }

    @Test
    void testInvalidTimestampsFallbackToRawMetadata() throws Exception {
        var item = objectMapper.readTree("""
                {
                  "id":"item123",
                  "name":"Spec Doc",
                  "mimeType":"application/pdf",
                  "modifiedTime":"not-a-date",
                  "createdTime":"bad-created"
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
        assertThat(doc.getMetadata()
                .getString(FsDocMetadata.LAST_MODIFIED))
                        .isEqualTo("not-a-date");
        assertThat(doc.getMetadata()
                .getString("crawler.gdrive.createdTime"))
                        .isEqualTo("bad-created");
    }

    @Test
    void testFetchFileUsesServiceAccountTokenAndCachesIt()
            throws Exception {
        var f = new HttpQueueGoogleDriveFetcher();
        f.getConfiguration().setClientEmail("svc@example.com");
        f.getConfiguration().setPrivateKey(generatePrivateKeyPem());
        f.enqueueJson(200, """
                {
                  "access_token":"token-1",
                  "expires_in":3600
                }
                """);
        f.enqueueJson(200, """
                {
                  "id":"item123",
                  "name":"Doc A",
                  "mimeType":"application/pdf"
                }
                """);
        f.enqueueJson(200, """
                {
                  "id":"item456",
                  "name":"Doc B",
                  "mimeType":"application/pdf"
                }
                """);

        var response1 = (FileFetchResponse) f
                .fetch(new FileFetchRequest(
                        new Doc("gdrive://workspace-01/drives/drive123/items/item123"),
                        METADATA));
        var response2 = (FileFetchResponse) f
                .fetch(new FileFetchRequest(
                        new Doc("gdrive://workspace-01/drives/drive123/items/item456"),
                        METADATA));

        assertThat(response1.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(response2.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(f.requestedUris)
                .filteredOn(url -> url.contains(
                        "oauth2.googleapis.com"))
                .hasSize(1);
        assertThat(f.requestedUris)
                .filteredOn(url -> url
                        .contains("/files/item123"))
                .hasSize(1);
        assertThat(f.requestedUris)
                .filteredOn(url -> url
                        .contains("/files/item456"))
                .hasSize(1);
        assertThat(f.authHeaders)
                .filteredOn(header -> header
                        .startsWith("Bearer "))
                .contains("Bearer token-1");
    }

    @Test
    void testFetchFileBadStatusWhenTokenEndpointFails() throws Exception {
        var f = new HttpQueueGoogleDriveFetcher();
        f.getConfiguration().setClientEmail("svc@example.com");
        f.getConfiguration().setPrivateKey(generatePrivateKeyPem());
        f.enqueueJson(401, "{\"error\":\"unauthorized\"}");

        var response = (FileFetchResponse) f.fetch(new FileFetchRequest(
                new Doc("gdrive://workspace-01/drives/drive123/items/item123"),
                METADATA));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.BAD_STATUS);
    }

    @Test
    void testFetchFileThrowsWhenTokenResponseLacksAccessToken()
            throws Exception {
        var f = new HttpQueueGoogleDriveFetcher();
        f.getConfiguration().setClientEmail("svc@example.com");
        f.getConfiguration().setPrivateKey(generatePrivateKeyPem());
        f.enqueueJson(200, "{\"expires_in\":3600}");

        assertThatThrownBy(() -> f.fetch(new FileFetchRequest(
                new Doc("gdrive://workspace-01/drives/drive123/items/item123"),
                METADATA)))
                        .isInstanceOf(
                                com.norconex.crawler.core.fetch.FetchException.class)
                        .hasMessageContaining(
                                "Could not fetch Google Drive reference");
    }

    @Test
    void testFetchContentBytesSupportsAbsoluteUrl() throws Exception {
        var f = new HttpQueueGoogleDriveFetcher();
        f.getConfiguration().setClientEmail("svc@example.com");
        f.getConfiguration().setPrivateKey(generatePrivateKeyPem());
        f.enqueueJson(200, """
                {
                  "access_token":"token-absolute",
                  "expires_in":3600
                }
                """);
        f.enqueueBinary(200, "payload");

        var bytes = f.fetchContentBytes(
                "https://www.googleapis.com/drive/v3/files/item123?alt=media");

        assertThat(new String(bytes, StandardCharsets.UTF_8))
                .isEqualTo("payload");
        assertThat(f.requestedUris)
                .contains(
                        "https://www.googleapis.com/drive/v3/files/item123?alt=media");
    }

    @Test
    void testHelperRequestsAddScopeSpecificQueryParameters()
            throws Exception {
        var f = new HttpQueueGoogleDriveFetcher();
        f.getConfiguration().setClientEmail("svc@example.com");
        f.getConfiguration().setPrivateKey(generatePrivateKeyPem());
        f.enqueueJson(200, """
                {
                  "access_token":"token-helper",
                  "expires_in":3600
                }
                """);
        f.enqueueJson(200, "{\"changes\":[]}");
        f.enqueueJson(200, "{\"startPageToken\":\"drive-token\"}");

        f.fetchChangesNode(GoogleDriveReference.parse(
                "gdrive://workspace-01/users/user123"),
                "page-1");
        f.fetchStartPageToken(GoogleDriveReference.parse(
                "gdrive://workspace-01/drives/drive123"));

        assertThat(f.requestedUris)
                .anySatisfy(url -> assertThat(url)
                        .contains("restrictToMyDrive=true"));
        assertThat(f.requestedUris)
                .anySatisfy(url -> assertThat(url)
                        .contains("driveId=drive123"));
    }

    @Test
    void testSourceDeltaReturnsNotFoundWhenChangesFeedMissing()
            throws Exception {
        var f = new DeltaGoogleDriveFetcher() {
            @Override
            JsonNode fetchChangesNode(GoogleDriveReference ref,
                    String pageToken)
                    throws IOException {
                return null;
            }
        };
        f.setStartPageToken("start-token");
        f.fetcherStartup(mockSession(true,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA,
                "gdrive://workspace-01/drives/drive123"));

        var response = (FolderPathsFetchResponse) f.fetch(
                new FolderPathsFetchRequest(
                        new Doc("gdrive://workspace-01/drives/drive123")));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NOT_FOUND);
        assertThat(response.getChildPaths()).isEmpty();
    }

    @Test
    void testSourceDeltaFailsWhenMaxPagesExceeded() {
        var f = new DeltaGoogleDriveFetcher() {
            @Override
            JsonNode fetchChangesNode(GoogleDriveReference ref,
                    String pageToken)
                    throws IOException {
                return objectMapper.readTree("""
                        {
                          "changes":[],
                          "nextPageToken":"same-token"
                        }
                        """);
            }
        };
        f.setStartPageToken("same-token");
        f.fetcherStartup(mockSession(true,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA,
                "gdrive://workspace-01/drives/drive123"));

        assertThatThrownBy(() -> f.fetch(new FolderPathsFetchRequest(
                new Doc("gdrive://workspace-01/drives/drive123"))))
                        .isInstanceOf(
                                com.norconex.crawler.core.fetch.FetchException.class)
                        .hasRootCauseMessage(
                                "Exceeded maximum number of Google Drive changes pages (1000).");
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

        assertThatThrownBy(() -> fetcher.fetcherStartup(mockSession(
                true,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA,
                "gdrive://workspace-01/drives/drive123/items/item123")))
                        .isInstanceOf(CrawlerException.class)
                        .hasMessageContaining(
                                "Unsupported Google Drive SOURCE_DELTA")
                        .hasMessageContaining(
                                "user or shared-drive start reference");
    }

    @Test
    void testSourceDeltaUsesChangesFeedAndPersistsCursor()
            throws Exception {
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
        assertThat(f.requestedChangeTokens)
                .containsExactly("start-token");
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
        JsonNode fetchItemNode(GoogleDriveReference ref)
                throws IOException {
            return itemNode;
        }

        @Override
        JsonNode fetchChildrenNode(GoogleDriveReference ref,
                String pageToken)
                throws IOException {
            return childNodes.pollFirst();
        }
    }

    private static class DeltaGoogleDriveFetcher
            extends StubGoogleDriveFetcher {
        private String startPageToken;
        private boolean throwInvalidStoredCursor;
        private final Deque<JsonNode> changePages = new ArrayDeque<>();
        private final Deque<String> requestedChangeTokens =
                new ArrayDeque<>();

        void setStartPageToken(String token) {
            this.startPageToken = token;
        }

        @Override
        String fetchStartPageToken(GoogleDriveReference ref) {
            return startPageToken;
        }

        @Override
        JsonNode fetchChangesNode(GoogleDriveReference ref,
                String pageToken)
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

    private static class HttpQueueGoogleDriveFetcher
            extends GoogleDriveFetcher {
        private final Deque<HttpResponse<byte[]>> responses =
                new ArrayDeque<>();
        private final List<String> requestedUris = new ArrayList<>();
        private final List<String> authHeaders = new ArrayList<>();

        void enqueueJson(int statusCode, String body) {
            responses.add(httpResponse(statusCode,
                    body.getBytes(StandardCharsets.UTF_8),
                    Map.of("content-type", List.of(
                            "application/json"))));
        }

        void enqueueBinary(int statusCode, String body) {
            responses.add(httpResponse(statusCode,
                    body.getBytes(StandardCharsets.UTF_8),
                    Map.of("content-type",
                            List.of("application/octet-stream"))));
        }

        @Override
        HttpResponse<byte[]> sendRequest(HttpRequest request)
                throws IOException {
            requestedUris.add(request.uri().toString());
            authHeaders.add(request.headers()
                    .firstValue("Authorization")
                    .orElse(""));
            var next = responses.pollFirst();
            if (next == null) {
                throw new IOException(
                        "No mocked Google Drive response available.");
            }
            return next;
        }
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> httpResponse(
            int statusCode,
            byte[] body,
            Map<String, List<String>> headers) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        when(response.headers())
                .thenReturn(HttpHeaders.of(
                        new HashMap<>(headers),
                        (name, value) -> true));
        when(response.request()).thenReturn(HttpRequest.newBuilder()
                .uri(URI.create("https://www.googleapis.com/mock"))
                .build());
        return response;
    }

    private static String generatePrivateKeyPem() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        PrivateKey privateKey = pair.getPrivate();
        var encoded = Base64.getMimeEncoder(64,
                "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(privateKey.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + encoded
                + "\n-----END PRIVATE KEY-----";
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
