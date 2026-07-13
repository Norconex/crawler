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
package com.norconex.crawler.fs.fetch.impl.m365graph;

import static com.norconex.crawler.core.fetch.FetchDirective.DOCUMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
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
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.session.CrawlerAttributes;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.fs.FsTestUtil;
import com.norconex.crawler.fs.doc.FsDocMetadata;
import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.crawler.fs.fetch.FolderPathsFetchRequest;
import com.norconex.crawler.fs.fetch.FolderPathsFetchResponse;
import com.norconex.crawler.fs.fetch.FsPath;
import com.norconex.crawler.fs.fetch.impl.GenericFileFetchResponse;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.doc.DocMetaConstants;

@Timeout(30)
class M365GraphFetcherTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void testAcceptRequest() {
        var f = new M365GraphFetcher();
        assertThat(f.acceptRequest(new FileFetchRequest(new Doc(
                "m365sp://tenant/sites/site123/drives/drive123/items/item123"),
                DOCUMENT))).isTrue();
        assertThat(f.acceptRequest(new FileFetchRequest(new Doc(
                "m365od://tenant/users/user123/drives/drive123/items/item123"),
                DOCUMENT))).isTrue();
        assertThat(f.acceptRequest(new FileFetchRequest(
                new Doc("s3://bucket/key"), DOCUMENT)))
                        .isFalse();
    }

    @Test
    void testWriteRead() {
        assertThatNoException().isThrownBy(
                () -> BeanMapper.DEFAULT.assertWriteRead(
                        FsTestUtil.randomize(
                                M365GraphFetcherConfig.class)));
    }

    @Test
    void testSharePointReferenceParseAndFormat() {
        var ref = M365GraphReference.parse(
                "m365sp://tenant/sites/site123/drives/drive123/items/item123");
        assertThat(ref.kind()).isEqualTo(M365GraphReference.Kind.ITEM);
        assertThat(ref.mode())
                .isEqualTo(M365GraphReference.Mode.SHAREPOINT);
        assertThat(ref.tenantId()).isEqualTo("tenant");
        assertThat(ref.siteId()).isEqualTo("site123");
        assertThat(ref.driveId()).isEqualTo("drive123");
        assertThat(ref.itemId()).isEqualTo("item123");
        assertThat(ref.itemApiPath())
                .isEqualTo("/sites/site123/drives/drive123/items/item123");
        assertThat(ref.toReference())
                .isEqualTo(
                        "m365sp://tenant/sites/site123/drives/drive123/items/item123");
    }

    @Test
    void testOneDriveReferenceParseAndFormat() {
        var ref = M365GraphReference.parse(
                "m365od://tenant/users/user123/drives/drive123/items/item123");
        assertThat(ref.kind()).isEqualTo(M365GraphReference.Kind.ITEM);
        assertThat(ref.mode())
                .isEqualTo(M365GraphReference.Mode.ONEDRIVE);
        assertThat(ref.tenantId()).isEqualTo("tenant");
        assertThat(ref.userId()).isEqualTo("user123");
        assertThat(ref.driveId()).isEqualTo("drive123");
        assertThat(ref.itemId()).isEqualTo("item123");
        assertThat(ref.itemApiPath())
                .isEqualTo("/users/user123/drives/drive123/items/item123");
        assertThat(ref.toReference())
                .isEqualTo(
                        "m365od://tenant/users/user123/drives/drive123/items/item123");
    }

    @Test
    void testSharePointSiteEntryReference() {
        var ref = M365GraphReference
                .parse("m365sp://tenant/sites/site123");
        assertThat(ref.kind()).isEqualTo(M365GraphReference.Kind.SITE);
        assertThat(ref.isDiscoveryEntry()).isTrue();
        assertThat(ref.drivesApiPath())
                .isEqualTo("/sites/site123/drives");
        assertThat(ref.toReference())
                .isEqualTo("m365sp://tenant/sites/site123");
    }

    @Test
    void testSharePointSiteUrlEntryReference() {
        var ref = M365GraphReference.parse(
                "m365sp://tenant/siteurl?url=https%3A%2F%2Fcontoso.sharepoint.com%2Fsites%2Fengineering");
        assertThat(ref.kind())
                .isEqualTo(M365GraphReference.Kind.SITE_URL);
        assertThat(ref.siteUrl())
                .isEqualTo("https://contoso.sharepoint.com/sites/engineering");
        assertThat(ref.resolveSiteApiPath())
                .isEqualTo("/sites/contoso.sharepoint.com:/sites/engineering");
    }

    @Test
    void testOneDriveUserEntryReference() {
        var ref = M365GraphReference
                .parse("m365od://tenant/users/user123");
        assertThat(ref.kind()).isEqualTo(M365GraphReference.Kind.USER);
        assertThat(ref.isDiscoveryEntry()).isTrue();
        assertThat(ref.drivesApiPath())
                .isEqualTo("/users/user123/drives");
        assertThat(ref.toReference())
                .isEqualTo("m365od://tenant/users/user123");
    }

    @Test
    void testDriveReferenceApiAndChild() {
        var ref = M365GraphReference.parse(
                "m365od://tenant/users/user123/drives/drive123");
        assertThat(ref.kind()).isEqualTo(M365GraphReference.Kind.DRIVE);
        assertThat(ref.driveRootApiPath())
                .isEqualTo("/users/user123/drives/drive123/root");
        assertThat(ref.child("root").itemApiPath())
                .isEqualTo("/users/user123/drives/drive123/items/root");
    }

    @Test
    void testDefaultStatusClassification() {
        var fetcher = new M365GraphFetcher();
        assertThat(fetcher.isValidStatus(200)).isTrue();
        assertThat(fetcher.isValidStatus(404)).isFalse();
        assertThat(fetcher.isNotFoundStatus(404)).isTrue();
        assertThat(fetcher.isNativeRetryStatus(429)).isTrue();
        assertThat(fetcher.getConfiguration().isNativeRetryEnabled())
                .isFalse();
    }

    @Test
    void testCustomStatusClassification() {
        var fetcher = new M365GraphFetcher();
        fetcher.getConfiguration()
                .setValidStatusCodes(List.of(200, 206))
                .setNotFoundStatusCodes(List.of(404, 410))
                .setNativeRetryStatusCodes(List.of(429))
                .setNativeRetryEnabled(true);

        assertThat(fetcher.isValidStatus(206)).isTrue();
        assertThat(fetcher.isNotFoundStatus(410)).isTrue();
        assertThat(fetcher.isNativeRetryStatus(503)).isFalse();
        assertThat(fetcher.getConfiguration().isNativeRetryEnabled())
                .isTrue();
    }

    @Test
    void testSourceDeltaEnabledOnIncrementalStartup() {
        var fetcher = new M365GraphFetcher();
        fetcher.getConfiguration().setSourceDeltaExpansion(
                M365GraphFetcherConfig.SourceDeltaExpansion.SELF_ONLY);
        fetcher.fetcherStartup(mockSession(true,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA,
                "m365od://tenant/users/user123/drives/drive123"));

        assertThat(fetcher.isSourceDeltaEnabled()).isTrue();
    }

    @Test
    void testSourceDeltaDisabledWithoutIncrementalSourceDelta() {
        var fetcher = new M365GraphFetcher();
        fetcher.fetcherStartup(mockSession(true,
                CrawlerConfig.ChangeDiscovery.CRAWLER_SCAN,
                "m365od://tenant/users/user123/drives/drive123"));
        assertThat(fetcher.isSourceDeltaEnabled()).isFalse();

        fetcher.fetcherStartup(mockSession(false,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA,
                "m365od://tenant/users/user123/drives/drive123"));
        assertThat(fetcher.isSourceDeltaEnabled()).isFalse();
    }

    @Test
    void testSourceDeltaRejectsSiteBoundaryWithSelfOnlyExpansion() {
        var fetcher = new M365GraphFetcher();
        fetcher.getConfiguration().setSourceDeltaExpansion(
                M365GraphFetcherConfig.SourceDeltaExpansion.SELF_ONLY);

        assertThatThrownBy(() -> fetcher.fetcherStartup(mockSession(
                true,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA,
                "m365sp://tenant/sites/site123")))
                        .isInstanceOf(CrawlerException.class)
                        .hasMessageContaining(
                                "sourceDeltaExpansion")
                        .hasMessageContaining(
                                "INCLUDE_CHILD_DRIVES");
    }

    @Test
    void testSourceDeltaAllowsSiteBoundaryWithChildDriveExpansion() {
        var fetcher = new M365GraphFetcher();
        fetcher.getConfiguration().setSourceDeltaExpansion(
                M365GraphFetcherConfig.SourceDeltaExpansion.INCLUDE_CHILD_DRIVES);

        assertThatNoException().isThrownBy(() -> fetcher.fetcherStartup(
                mockSession(true,
                        CrawlerConfig.ChangeDiscovery.SOURCE_DELTA,
                        "m365sp://tenant/sites/site123")));
        assertThat(fetcher.isSourceDeltaEnabled()).isTrue();
    }

    @Test
    void testSourceDeltaRejectsItemBoundary() {
        var fetcher = new M365GraphFetcher();

        assertThatThrownBy(() -> fetcher.fetcherStartup(mockSession(
                true,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA,
                "m365od://tenant/users/user123/drives/drive123/items/item123")))
                        .isInstanceOf(CrawlerException.class)
                        .hasMessageContaining(
                                "Unsupported M365 SOURCE_DELTA")
                        .hasMessageContaining(
                                "drive start reference");
    }

    @Test
    void testRejectInvalidReference() {
        assertThatThrownBy(() -> M365GraphReference.parse(
                "m365sp://tenant/invalid/path"))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(
                                "Invalid SharePoint reference");
    }

    @Test
    void testFetchMetadataPopulatesFields() throws Exception {
        var fetcher = new M365GraphFetcher();
        var doc = new Doc("m365sp://tenant/sites/s/drives/d/items/i");
        JsonNode item = JSON.readTree("""
                {
                  "size": 123,
                  "lastModifiedDateTime": "2026-07-12T12:34:56Z",
                  "createdDateTime": "2026-01-01T00:00:00Z",
                  "webUrl": "https://contoso/share/doc",
                  "file": {"mimeType": "application/pdf"},
                  "lastModifiedBy": {"user": {"displayName": "Ada", "id": "u1"}},
                  "parentReference": {"path": "/drives/d/root:/folder"}
                }
                """);

        fetcher.fetchMetadata(doc, item);

        assertThat(doc.getMetadata().getLong(FsDocMetadata.FILE_SIZE))
                .isEqualTo(123L);
        assertThat(doc.getMetadata()
                .getLong(FsDocMetadata.LAST_MODIFIED))
                        .isGreaterThan(0L);
        assertThat(doc.getMetadata().getString("crawler.m365.created"))
                .contains("2026-01-01T00:00:00Z");
        assertThat(doc.getMetadata().getString("crawler.m365.webUrl"))
                .contains("https://contoso/share/doc");
        assertThat(doc.getMetadata()
                .getString(DocMetaConstants.CONTENT_TYPE))
                        .contains("application/pdf");
        assertThat(doc.getContentType()).isNotNull();
        assertThat(
                doc.getMetadata().getString(
                        "crawler.m365.owner.displayName"))
                                .contains("Ada");
        assertThat(doc.getMetadata().getString("crawler.m365.owner.id"))
                .contains("u1");
        assertThat(doc.getMetadata()
                .getString("crawler.m365.parent.path"))
                        .contains("/drives/d/root:/folder");
    }

    @Test
    void testDeltaCursorLifecycleAndStoredChildDriveRefs() {
        var attrs = mock(CrawlerAttributes.class);
        when(attrs.getString("m365graph.delta.cursor."
                + "m365od://tenant/users/u/drives/d"))
                        .thenReturn(Optional.of(
                                "https://graph/delta"));
        when(attrs.getString("m365graph.delta.childDrives."
                + "m365sp://tenant/sites/s"))
                        .thenReturn(Optional
                                .of("[\"m365sp://tenant/sites/s/drives/d1\"]"));

        var fetcher = new M365GraphFetcher();
        fetcher.fetcherStartup(mockSession(true,
                CrawlerConfig.ChangeDiscovery.SOURCE_DELTA,
                "m365od://tenant/users/u/drives/d"));
        setSessionAttributes(fetcher, attrs);

        var driveRef =
                M365GraphReference.parse(
                        "m365od://tenant/users/u/drives/d");
        var siteRef = M365GraphReference
                .parse("m365sp://tenant/sites/s");

        assertThat(fetcher.getDeltaCursor(driveRef))
                .contains("https://graph/delta");
        fetcher.setDeltaCursor(driveRef, "https://graph/newdelta");
        verify(attrs).setString(
                "m365graph.delta.cursor.m365od://tenant/users/u/drives/d",
                "https://graph/newdelta");
        fetcher.clearDeltaCursor(driveRef);
        verify(attrs).setString(
                "m365graph.delta.cursor.m365od://tenant/users/u/drives/d",
                "");

        assertThat(fetcher.getStoredChildDriveRefs(siteRef))
                .containsExactly(
                        "m365sp://tenant/sites/s/drives/d1");
        fetcher.setStoredChildDriveRefs(siteRef,
                java.util.Set.of(
                        "m365sp://tenant/sites/s/drives/d2"));
        verify(attrs).setString(org.mockito.ArgumentMatchers.eq(
                "m365graph.delta.childDrives.m365sp://tenant/sites/s"),
                org.mockito.ArgumentMatchers.contains("d2"));
    }

    @Test
    void testGetStoredChildDriveRefsInvalidJsonThrows() {
        var attrs = mock(CrawlerAttributes.class);
        when(attrs.getString("m365graph.delta.childDrives."
                + "m365sp://tenant/sites/s"))
                        .thenReturn(Optional.of(
                                "not-json"));

        var fetcher = new M365GraphFetcher();
        setSessionAttributes(fetcher, attrs);

        assertThatThrownBy(() -> fetcher.getStoredChildDriveRefs(
                M365GraphReference.parse(
                        "m365sp://tenant/sites/s")))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessageContaining(
                                        "Could not read persisted");
    }

    @Test
    void testFetchFileRejectsTenantMismatchEarly() {
        var fetcher = new M365GraphFetcher();
        fetcher.getConfiguration().setTenantId("tenant-A");

        var req = new FileFetchRequest(new Doc(
                "m365sp://tenant-B/sites/s/drives/d/items/i"),
                DOCUMENT);

        assertThatThrownBy(() -> fetcher.fetch(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "Reference tenant does not match");
    }

    @Test
    void testFetchItemNodeStatusHandling() throws Exception {
        var ref = M365GraphReference.parse(
                "m365od://tenant/users/u/drives/d/items/i");

        var notFoundFetcher =
                new StubM365Fetcher(stubResponse(404, "{}"));
        setToken(notFoundFetcher, "token");
        assertThat(notFoundFetcher.fetchItemNode(ref)).isNull();

        var badStatusFetcher =
                new StubM365Fetcher(stubResponse(500, "{}"));
        setToken(badStatusFetcher, "token");
        assertThatThrownBy(() -> badStatusFetcher.fetchItemNode(ref))
                .isInstanceOf(M365GraphFetcher.GraphHttpStatusException.class);

        var okFetcher =
                new StubM365Fetcher(stubResponse(200,
                        "{\"id\":\"i\"}"));
        setToken(okFetcher, "token");
        assertThat(okFetcher.fetchItemNode(ref).path("id").asText())
                .isEqualTo("i");
    }

    @Test
    void testFetchFileNonItemReturnsFolderResponse() throws Exception {
        var fetcher = new M365GraphFetcher();
        var req = new FileFetchRequest(
                new Doc("m365sp://tenant/sites/site123"),
                DOCUMENT);

        var resp = (GenericFileFetchResponse) fetcher.fetch(req);
        assertThat(resp.isFolder()).isTrue();
        assertThat(resp.isFile()).isFalse();
    }

    @Test
    void testFetchChildPaths() throws Exception {
        var fetcher = new StubFetchGraphFetcher();
        fetcher.setChildrenJson("""
                {
                  "value": [
                    { "id": "file1", "file": { "mimeType": "text/plain" } },
                    { "id": "folder1", "folder": { "childCount": 3 } }
                  ]
                }
                """);

        var response = (FolderPathsFetchResponse) fetcher.fetch(
                new FolderPathsFetchRequest(new Doc(
                        "m365od://tenant/users/user123/drives/drive123/items/root")));

        assertThat(response.getProcessingOutcome().isGoodState())
                .isTrue();
        assertThat(response.getChildPaths())
                .extracting(p -> p.getUri() + ":"
                        + p.isFile() + ":"
                        + p.isFolder())
                .containsExactlyInAnyOrder(
                        "m365od://tenant/users/user123/drives/drive123/items/file1:true:false",
                        "m365od://tenant/users/user123/drives/drive123/items/folder1:false:true");
    }

    @Test
    void testFetchSharePointSiteUrlEntryDrives() throws Exception {
        var fetcher = new StubFetchGraphFetcher();
        fetcher.setResolvedSiteJson("""
                { "id": "siteResolved" }
                """);
        fetcher.setDrivesJson("""
                {
                  "value": [
                    { "id": "driveZ" }
                  ]
                }
                """);

        var response = (FolderPathsFetchResponse) fetcher.fetch(
                new FolderPathsFetchRequest(new Doc(
                        "m365sp://tenant/siteurl?url=https%3A%2F%2Fcontoso.sharepoint.com%2Fsites%2Fengineering")));

        assertThat(response.getProcessingOutcome().isGoodState())
                .isTrue();
        assertThat(response.getChildPaths())
                .extracting(FsPath::getUri)
                .containsExactlyInAnyOrder(
                        "m365sp://tenant/sites/siteResolved/drives/driveZ");
    }

    @Test
    void testDriveDeltaPaginationAndCursorPersistence() throws Exception {
        var fetcher = new StubFetchGraphFetcher();
        fetcher.setSourceDeltaEnabledForTest(true);
        fetcher.setDeltaJson(
                "/users/user123/drives/drive123/root/delta", """
                {
                  "value": [
                    { "id": "fileA", "file": { "mimeType": "text/plain" } }
                  ],
                  "@odata.nextLink": "https://graph.microsoft.com/v1.0/page2"
                }
                """);
        fetcher.setDeltaJson("https://graph.microsoft.com/v1.0/page2", """
                {
                  "value": [
                    { "id": "folderA", "folder": { "childCount": 1 } },
                    { "id": "deletedA", "deleted": { "state": "deleted" } }
                  ],
                  "@odata.deltaLink": "https://graph.microsoft.com/v1.0/deltaToken-1"
                }
                """);

        var driveRef = M365GraphReference.parse(
                "m365od://tenant/users/user123/drives/drive123");
        var response = (FolderPathsFetchResponse) fetcher.fetch(
                new FolderPathsFetchRequest(new Doc(
                        driveRef.toReference())));

        assertThat(response.getProcessingOutcome().isGoodState())
                .isTrue();
        assertThat(response.getChildPaths())
                .extracting(
                        p -> p.getUri() + ":"
                                + p.isFile()
                                + ":"
                                + p.isFolder())
                .containsExactlyInAnyOrder(
                        "m365od://tenant/users/user123/drives/drive123/items/fileA:true:false",
                        "m365od://tenant/users/user123/drives/drive123/items/folderA:false:true",
                        "m365od://tenant/users/user123/drives/drive123/items/deletedA:true:false");
        assertThat(fetcher.getStoredDeltaCursor(driveRef))
                .isEqualTo("https://graph.microsoft.com/v1.0/deltaToken-1");
    }

    @Test
    void testDriveDeltaInvalidStoredCursorFallsBackToFreshDelta()
            throws Exception {
        var fetcher = new StubFetchGraphFetcher();
        fetcher.setSourceDeltaEnabledForTest(true);
        var driveRef = M365GraphReference.parse(
                "m365od://tenant/users/user123/drives/drive123");
        fetcher.setStoredDeltaCursor(
                driveRef,
                "https://graph.microsoft.com/v1.0/stale-token");
        fetcher.setDeltaException(
                "https://graph.microsoft.com/v1.0/stale-token",
                new M365GraphFetcher.GraphHttpStatusException(
                        410,
                        "stale delta token"));
        fetcher.setDeltaJson(
                "/users/user123/drives/drive123/root/delta", """
                {
                  "value": [
                    { "id": "fileB", "file": { "mimeType": "text/plain" } }
                  ],
                  "@odata.deltaLink": "https://graph.microsoft.com/v1.0/deltaToken-2"
                }
                """);

        var response = (FolderPathsFetchResponse) fetcher.fetch(
                new FolderPathsFetchRequest(new Doc(
                        driveRef.toReference())));

        assertThat(response.getProcessingOutcome().isGoodState())
                .isTrue();
        assertThat(response.getChildPaths())
                .extracting(FsPath::getUri)
                .containsExactlyInAnyOrder(
                        "m365od://tenant/users/user123/drives/drive123/items/fileB");
        assertThat(fetcher.getStoredDeltaCursor(driveRef))
                .isEqualTo("https://graph.microsoft.com/v1.0/deltaToken-2");
    }

    private static CrawlerSession mockSession(
            boolean incremental,
            CrawlerConfig.ChangeDiscovery changeDiscovery,
            String... startReferences) {
        var config = new CrawlerConfig()
                .setChangeDiscovery(changeDiscovery)
                .setStartReferences(List.of(startReferences));
        var context = mock(CrawlerContext.class);
        when(context.getCrawlConfig()).thenReturn(config);

        var sessionAttributes = mock(CrawlerAttributes.class);
        var session = mock(CrawlerSession.class);
        when(session.getCrawlContext()).thenReturn(context);
        when(session.getSessionAttributes())
                .thenReturn(sessionAttributes);
        when(session.isIncremental()).thenReturn(incremental);
        return session;
    }

    private static void setSessionAttributes(
            M365GraphFetcher fetcher,
            CrawlerAttributes attrs) {
        try {
            var f = M365GraphFetcher.class
                    .getDeclaredField("sessionAttributes");
            f.setAccessible(true);
            f.set(fetcher, attrs);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setToken(M365GraphFetcher fetcher, String token)
            throws Exception {
        Field tokenField =
                M365GraphFetcher.class.getDeclaredField(
                        "accessToken");
        tokenField.setAccessible(true);
        tokenField.set(fetcher, token);

        Field expiryField = M365GraphFetcher.class
                .getDeclaredField("accessTokenExpiry");
        expiryField.setAccessible(true);
        expiryField.set(fetcher, Instant.now().plusSeconds(3600));
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> stubResponse(int status,
            String body) {
        var response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(response.headers())
                .thenReturn(HttpHeaders.of(Map.of(),
                        (a, b) -> true));
        return (HttpResponse<byte[]>) response;
    }

    private static final class StubM365Fetcher extends M365GraphFetcher {
        private final HttpResponse<byte[]> response;

        private StubM365Fetcher(HttpResponse<byte[]> response) {
            this.response = response;
        }

        @Override
        HttpResponse<byte[]> sendRequest(HttpRequest request)
                throws IOException {
            return response;
        }
    }

    private static final class StubFetchGraphFetcher
            extends M365GraphFetcher {
        private JsonNode children;
        private JsonNode drives;
        private JsonNode resolvedSite;
        private final Map<String, JsonNode> deltaPages =
                new HashMap<>();
        private final Map<String, IOException> deltaErrors =
                new HashMap<>();
        private final Map<String, String> deltaCursors =
                new HashMap<>();
        private boolean sourceDeltaEnabledForTest;

        private StubFetchGraphFetcher() {
            getConfiguration()
                    .setTenantId("tenant")
                    .setClientId("client-id")
                    .setClientSecret("secret");
        }

        private void setChildrenJson(String json) throws Exception {
            children = JSON.readTree(json);
        }

        private void setDrivesJson(String json) throws Exception {
            drives = JSON.readTree(json);
        }

        private void setResolvedSiteJson(String json) throws Exception {
            resolvedSite = JSON.readTree(json);
        }

        private void setDeltaJson(String pathOrUrl, String json)
                throws Exception {
            deltaPages.put(pathOrUrl, JSON.readTree(json));
        }

        private void setDeltaException(String pathOrUrl,
                IOException e) {
            deltaErrors.put(pathOrUrl, e);
        }

        private void setSourceDeltaEnabledForTest(boolean enabled) {
            sourceDeltaEnabledForTest = enabled;
        }

        private String getStoredDeltaCursor(M365GraphReference ref) {
            return deltaCursors.get(ref.toReference());
        }

        private void setStoredDeltaCursor(M365GraphReference ref,
                String cursor) {
            deltaCursors.put(ref.toReference(), cursor);
        }

        @Override
        JsonNode fetchChildrenNode(M365GraphReference ref) {
            return children;
        }

        @Override
        JsonNode fetchDrivesNode(M365GraphReference ref) {
            return drives;
        }

        @Override
        JsonNode resolveSiteNode(M365GraphReference ref) {
            return resolvedSite;
        }

        @Override
        JsonNode fetchDeltaNode(String pathOrUrl) throws IOException {
            if (deltaErrors.containsKey(pathOrUrl)) {
                throw deltaErrors.get(pathOrUrl);
            }
            return deltaPages.get(pathOrUrl);
        }

        @Override
        boolean isSourceDeltaEnabled() {
            return sourceDeltaEnabledForTest;
        }

        @Override
        Optional<String> getDeltaCursor(M365GraphReference ref) {
            return Optional.ofNullable(
                    deltaCursors.get(ref.toReference()));
        }

        @Override
        void setDeltaCursor(M365GraphReference ref, String cursor) {
            deltaCursors.put(ref.toReference(), cursor);
        }

        @Override
        void clearDeltaCursor(M365GraphReference ref) {
            deltaCursors.remove(ref.toReference());
        }
    }
}
