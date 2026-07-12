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

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.norconex.crawler.fs.doc.FsDocMetadata;
import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.crawler.fs.fetch.FileFetchResponse;
import com.norconex.crawler.fs.fetch.FolderPathsFetchRequest;
import com.norconex.crawler.fs.fetch.FolderPathsFetchResponse;
import com.norconex.crawler.fs.fetch.FsPath;
import com.norconex.importer.doc.Doc;

@Timeout(30)
class M365GraphFetcherIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void testFetchFileObject() throws Exception {
        var fetcher = new StubFetcher();
        fetcher.setItemJson("""
                {
                  "id": "item123",
                  "size": 10,
                  "lastModifiedDateTime": "2026-01-01T00:00:00Z",
                  "createdDateTime": "2025-12-31T00:00:00Z",
                  "webUrl": "https://contoso.sharepoint.com/file.txt",
                  "file": { "mimeType": "text/plain" },
                  "lastModifiedBy": { "user": { "id": "u1", "displayName": "User One" } },
                  "parentReference": { "path": "/drives/drive123/root:/docs" }
                }
                """);
        fetcher.setContent("Bye World!");

        var doc = new Doc(
                "m365sp://tenant/sites/site123/drives/drive123/items/item123");
        var response = (FileFetchResponse) fetcher.fetch(
                new FileFetchRequest(doc, DOCUMENT));

        assertThat(response.getProcessingOutcome().isGoodState()).isTrue();
        assertThat(response.isFile()).isTrue();
        assertThat(response.isFolder()).isFalse();
        assertThat(doc.getMetadata().getString(FsDocMetadata.FILE_SIZE))
                .isEqualTo("10");
        assertThat(new String(doc.getInputStream().readAllBytes()))
                .contains("Bye World!");
    }

    @Test
    void testFetchChildPaths() throws Exception {
        var fetcher = new StubFetcher();
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

        assertThat(response.getProcessingOutcome().isGoodState()).isTrue();
        assertThat(response.getChildPaths())
                .extracting(p -> p.getUri() + ":"
                        + p.isFile() + ":"
                        + p.isFolder())
                .containsExactlyInAnyOrder(
                        "m365od://tenant/users/user123/drives/drive123/items/file1:true:false",
                        "m365od://tenant/users/user123/drives/drive123/items/folder1:false:true");
    }

    @Test
    void testFetchSharePointSiteEntryDrives() throws Exception {
        var fetcher = new StubFetcher();
        fetcher.setDrivesJson("""
                {
                  "value": [
                    { "id": "driveA" },
                    { "id": "driveB" }
                  ]
                }
                """);

        var response = (FolderPathsFetchResponse) fetcher.fetch(
                new FolderPathsFetchRequest(
                        new Doc("m365sp://tenant/sites/site123")));

        assertThat(response.getProcessingOutcome().isGoodState()).isTrue();
        assertThat(response.getChildPaths())
                .extracting(
                        p -> p.getUri() + ":" + p.isFile() + ":" + p.isFolder())
                .containsExactlyInAnyOrder(
                        "m365sp://tenant/sites/site123/drives/driveA:false:true",
                        "m365sp://tenant/sites/site123/drives/driveB:false:true");
    }

    @Test
    void testFetchOneDriveUserEntryDrives() throws Exception {
        var fetcher = new StubFetcher();
        fetcher.setDrivesJson("""
                {
                  "value": [
                    { "id": "drive123" }
                  ]
                }
                """);

        var response = (FolderPathsFetchResponse) fetcher.fetch(
                new FolderPathsFetchRequest(
                        new Doc("m365od://tenant/users/user123")));

        assertThat(response.getProcessingOutcome().isGoodState()).isTrue();
        assertThat(response.getChildPaths())
                .extracting(p -> p.getUri())
                .containsExactlyInAnyOrder(
                        "m365od://tenant/users/user123/drives/drive123");
    }

    @Test
    void testFetchSharePointSiteUrlEntryDrives() throws Exception {
        var fetcher = new StubFetcher();
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

        assertThat(response.getProcessingOutcome().isGoodState()).isTrue();
        assertThat(response.getChildPaths())
                .extracting(p -> p.getUri())
                .containsExactlyInAnyOrder(
                        "m365sp://tenant/sites/siteResolved/drives/driveZ");
    }

    @Test
    void testDriveDeltaPaginationAndCursorPersistence() throws Exception {
        var fetcher = new StubFetcher();
        fetcher.setSourceDeltaEnabledForTest(true);
        fetcher.setDeltaJson("/users/user123/drives/drive123/root/delta", """
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

        var response = (FolderPathsFetchResponse) fetcher.fetch(
                new FolderPathsFetchRequest(
                        new Doc("m365od://tenant/users/user123/drives/drive123")));

        assertThat(response.getProcessingOutcome().isGoodState()).isTrue();
        assertThat(response.getChildPaths())
                .extracting(
                        p -> p.getUri() + ":" + p.isFile() + ":" + p.isFolder())
                .containsExactlyInAnyOrder(
                        "m365od://tenant/users/user123/drives/drive123/items/fileA:true:false",
                        "m365od://tenant/users/user123/drives/drive123/items/folderA:false:true",
                        "m365od://tenant/users/user123/drives/drive123/items/deletedA:true:false");
        assertThat(fetcher.getStoredDeltaCursor(
                M365GraphReference.parse(
                        "m365od://tenant/users/user123/drives/drive123")))
                                .isEqualTo(
                                        "https://graph.microsoft.com/v1.0/deltaToken-1");
    }

    @Test
    void testDriveDeltaInvalidStoredCursorFallsBackToFreshDelta()
            throws Exception {
        var fetcher = new StubFetcher();
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
        fetcher.setDeltaJson("/users/user123/drives/drive123/root/delta", """
                {
                  "value": [
                    { "id": "fileB", "file": { "mimeType": "text/plain" } }
                  ],
                  "@odata.deltaLink": "https://graph.microsoft.com/v1.0/deltaToken-2"
                }
                """);

        var response = (FolderPathsFetchResponse) fetcher.fetch(
                new FolderPathsFetchRequest(new Doc(driveRef.toReference())));

        assertThat(response.getProcessingOutcome().isGoodState()).isTrue();
        assertThat(response.getChildPaths())
                .extracting(FsPath::getUri)
                .containsExactlyInAnyOrder(
                        "m365od://tenant/users/user123/drives/drive123/items/fileB");
        assertThat(fetcher.getStoredDeltaCursor(driveRef))
                .isEqualTo("https://graph.microsoft.com/v1.0/deltaToken-2");
    }

    private static class StubFetcher extends M365GraphFetcher {
        private com.fasterxml.jackson.databind.JsonNode item;
        private com.fasterxml.jackson.databind.JsonNode children;
        private com.fasterxml.jackson.databind.JsonNode drives;
        private com.fasterxml.jackson.databind.JsonNode resolvedSite;
        private byte[] content = new byte[0];
        private final Map<String,
                com.fasterxml.jackson.databind.JsonNode> deltaPages =
                        new HashMap<>();
        private final Map<String, IOException> deltaErrors = new HashMap<>();
        private final Map<String, String> deltaCursors = new HashMap<>();
        private boolean sourceDeltaEnabledForTest;

        StubFetcher() {
            getConfiguration()
                    .setTenantId("tenant")
                    .setClientId("client-id")
                    .setClientSecret("secret");
        }

        void setItemJson(String json) throws Exception {
            item = MAPPER.readTree(json);
        }

        void setChildrenJson(String json) throws Exception {
            children = MAPPER.readTree(json);
        }

        void setDrivesJson(String json) throws Exception {
            drives = MAPPER.readTree(json);
        }

        void setResolvedSiteJson(String json) throws Exception {
            resolvedSite = MAPPER.readTree(json);
        }

        void setContent(String body) {
            content = body.getBytes();
        }

        void setDeltaJson(String pathOrUrl, String json) throws Exception {
            deltaPages.put(pathOrUrl, MAPPER.readTree(json));
        }

        void setDeltaException(String pathOrUrl, IOException e) {
            deltaErrors.put(pathOrUrl, e);
        }

        void setSourceDeltaEnabledForTest(boolean enabled) {
            sourceDeltaEnabledForTest = enabled;
        }

        void setStoredDeltaCursor(M365GraphReference ref, String cursor) {
            deltaCursors.put(ref.toReference(), cursor);
        }

        String getStoredDeltaCursor(M365GraphReference ref) {
            return deltaCursors.get(ref.toReference());
        }

        @Override
        com.fasterxml.jackson.databind.JsonNode fetchItemNode(
                M365GraphReference ref) {
            return item;
        }

        @Override
        com.fasterxml.jackson.databind.JsonNode fetchChildrenNode(
                M365GraphReference ref) {
            return children;
        }

        @Override
        com.fasterxml.jackson.databind.JsonNode fetchDrivesNode(
                M365GraphReference ref) {
            return drives;
        }

        @Override
        com.fasterxml.jackson.databind.JsonNode resolveSiteNode(
                M365GraphReference ref) {
            return resolvedSite;
        }

        @Override
        byte[] fetchContentBytes(M365GraphReference ref) {
            return content;
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
        java.util.Optional<String> getDeltaCursor(M365GraphReference ref) {
            return java.util.Optional
                    .ofNullable(deltaCursors.get(ref.toReference()));
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
