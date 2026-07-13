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
package com.norconex.crawler.fs.fetch.impl.box;

import static com.norconex.crawler.core.fetch.FetchDirective.DOCUMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.fs.doc.FsDocMetadata;
import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.crawler.fs.fetch.FileFetchResponse;
import com.norconex.crawler.fs.fetch.FolderPathsFetchRequest;
import com.norconex.crawler.fs.fetch.FolderPathsFetchResponse;
import com.norconex.importer.doc.Doc;

@Timeout(30)
class BoxFetcherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testAcceptRequest() {
        var f = new StubBoxFetcher();

        assertThat(f.accept(new FileFetchRequest(
                new Doc("box://ent-01/folders/0/items/123"),
                DOCUMENT))).isTrue();
        assertThat(f.accept(new FileFetchRequest(
                new Doc("gdrive://tenant/drives/abc/items/123"),
                DOCUMENT))).isFalse();
    }

    @Test
    void testFetchFileItem() throws Exception {
        var f = new StubBoxFetcher();
        f.item = new BoxFetcher.BoxItem(objectMapper.readTree("""
                {
                  "id":"123",
                  "type":"file",
                  "name":"sample.txt",
                  "etag":"1",
                  "sha1":"abc",
                  "size":42,
                  "created_at":"2026-07-11T10:00:00Z",
                  "modified_at":"2026-07-11T11:00:00Z",
                  "parent":{"id":"0"}
                }
                """), true, false);

        var doc = new Doc("box://ent-01/folders/0/items/123");
        var response = (FileFetchResponse) f.fetch(
                new FileFetchRequest(doc, DOCUMENT));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(response.isFile()).isTrue();
        assertThat(response.isFolder()).isFalse();
        assertThat(f.lastContentPath).isEqualTo("/files/123/content");
        assertThat(doc.getInputStream()).isNotNull();
        assertThat(doc.getMetadata().getString("crawler.box.id"))
                .isEqualTo("123");
        assertThat(doc.getMetadata().getString("crawler.box.type"))
                .isEqualTo("file");
    }

    @Test
    void testFetchFileNonItemReferenceReturnsFolder() throws Exception {
        var f = new StubBoxFetcher();

        var response = (FileFetchResponse) f.fetch(
                new FileFetchRequest(new Doc(
                        "box://ent-01/folders/0"),
                        DOCUMENT));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(response.isFolder()).isTrue();
        assertThat(response.isFile()).isFalse();
    }

    @Test
    void testFetchFileNotFoundWhenItemMissing() throws Exception {
        var f = new StubBoxFetcher();

        var response = (FileFetchResponse) f.fetch(
                new FileFetchRequest(
                        new Doc("box://ent-01/folders/0/items/missing"),
                        DOCUMENT));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NOT_FOUND);
    }

    @Test
    void testFetchFileFolderSkipsContentAndSupportsNullContentBytes()
            throws Exception {
        var folderFetcher = new StubBoxFetcher();
        folderFetcher.item =
                new BoxFetcher.BoxItem(objectMapper.readTree("""
                {
                  "id":"200",
                  "type":"folder",
                  "name":"my-folder"
                }
                """), false, true);

        var folderDoc = new Doc("box://ent-01/folders/0/items/200");
        var folderResponse = (FileFetchResponse) folderFetcher.fetch(
                new FileFetchRequest(folderDoc, DOCUMENT));

        assertThat(folderResponse.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(folderResponse.isFolder()).isTrue();
        assertThat(folderFetcher.lastContentPath).isNull();

        var fileFetcher = new StubBoxFetcher();
        fileFetcher.item = new BoxFetcher.BoxItem(objectMapper.readTree("""
                {
                  "id":"300",
                  "type":"file",
                  "name":"null-content.txt"
                }
                """), true, false);
        fileFetcher.returnNullContentBytes = true;
        var fileDoc = new Doc("box://ent-01/folders/0/items/300");

        var fileResponse = (FileFetchResponse) fileFetcher.fetch(
                new FileFetchRequest(fileDoc, DOCUMENT));

        assertThat(fileResponse.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(fileFetcher.lastContentPath)
                .isEqualTo("/files/300/content");
    }

    @Test
    void testFetchFileBadStatusAndIoHandling() throws Exception {
        var badStatusFetcher = new StubBoxFetcher();
        badStatusFetcher.throwItemStatus = true;

        var badStatusResponse =
                (FileFetchResponse) badStatusFetcher.fetch(
                        new FileFetchRequest(
                                new Doc("box://ent-01/folders/0/items/123"),
                                DOCUMENT));
        assertThat(badStatusResponse.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.BAD_STATUS);

        var ioFetcher = new StubBoxFetcher();
        ioFetcher.throwItemIo = true;
        assertThatThrownBy(() -> ioFetcher.fetch(
                new FileFetchRequest(
                        new Doc("box://ent-01/folders/0/items/123"),
                        DOCUMENT)))
                                .isInstanceOf(
                                        com.norconex.crawler.core.fetch.FetchException.class)
                                .hasMessageContaining(
                                        "Could not fetch Box reference");
    }

    @Test
    void testFetchFolderReturnsChildPathsAcrossPages() throws Exception {
        var f = new StubBoxFetcher();
        f.folderPages.add(objectMapper.readTree("""
                {
                  "entries":[
                    {"id":"100","type":"file"},
                    {"id":"200","type":"folder"}
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
                new FolderPathsFetchRequest(new Doc(
                        "box://ent-01/folders/0")));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(response.getChildPaths())
                .extracting(path -> path.getUri())
                .containsExactlyInAnyOrder(
                        "box://ent-01/folders/0/items/100",
                        "box://ent-01/folders/0/items/200",
                        "box://ent-01/folders/0/items/300");
    }

    @Test
    void testFetchFolderNotFound() throws Exception {
        var f = new StubBoxFetcher();
        f.returnNullFolderPage = true;

        var response = (FolderPathsFetchResponse) f.fetch(
                new FolderPathsFetchRequest(
                        new Doc("box://ent-01/folders/999")));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NOT_FOUND);
    }

    @Test
    void testFetchFolderBadStatusAndIoHandling() throws Exception {
        var badStatusFetcher = new StubBoxFetcher();
        badStatusFetcher.throwFolderStatus = true;

        var badStatus = (FolderPathsFetchResponse) badStatusFetcher
                .fetch(
                        new FolderPathsFetchRequest(
                                new Doc("box://ent-01/folders/0")));
        assertThat(badStatus.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.BAD_STATUS);
        assertThat(badStatus.getChildPaths()).isEmpty();

        var ioFetcher = new StubBoxFetcher();
        ioFetcher.throwFolderIo = true;
        assertThatThrownBy(() -> ioFetcher.fetch(
                new FolderPathsFetchRequest(new Doc(
                        "box://ent-01/folders/0"))))
                                .isInstanceOf(
                                        com.norconex.crawler.core.fetch.FetchException.class)
                                .hasMessageContaining(
                                        "Could not fetch Box child references");
    }

    @Test
    void testFetchFolderIgnoresMissingIdAndUnknownType() throws Exception {
        var f = new StubBoxFetcher();
        f.folderPages.add(objectMapper.readTree("""
                {
                  "entries":[
                    {"id":"100","type":"file"},
                    {"id":"200","type":"folder"},
                    {"id":"300","type":"web_link"},
                    {"type":"file"}
                  ],
                  "limit":0,
                  "total_count":4
                }
                """));

        var response = (FolderPathsFetchResponse) f.fetch(
                new FolderPathsFetchRequest(new Doc(
                        "box://ent-01/folders/0")));

        assertThat(response.getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.NEW);
        assertThat(response.getChildPaths())
                .extracting(path -> path.getUri() + ":"
                        + path.isFile() + ":"
                        + path.isFolder())
                .containsExactlyInAnyOrder(
                        "box://ent-01/folders/0/items/100:true:false",
                        "box://ent-01/folders/0/items/200:false:true",
                        "box://ent-01/folders/0/items/300:false:false");
    }

    @Test
    void testFetchMetadataStoresInvalidDateFallback() throws Exception {
        var f = new StubBoxFetcher();
        var doc = new Doc("box://ent-01/folders/0/items/123");
        var item = new BoxFetcher.BoxItem(objectMapper.readTree("""
                {
                  "id":"123",
                  "type":"file",
                  "created_at":"not-a-date",
                  "modified_at":"invalid-modified"
                }
                """), true, false);

        f.fetchMetadata(doc, item);

        assertThat(doc.getMetadata().getLong("crawler.box.created"))
                .isNull();
        assertThat(doc.getMetadata().getLong("fs.lastModified"))
                .isNull();
        assertThat(doc.getMetadata().getString(
                "crawler.box.invalidDate.crawler.box.created"))
                        .isEqualTo("not-a-date");
        assertThat(doc.getMetadata().getString(
                "crawler.box.invalidDate."
                        + FsDocMetadata.LAST_MODIFIED))
                                .isEqualTo("invalid-modified");
    }

    private static class StubBoxFetcher extends BoxFetcher {
        private final Deque<JsonNode> folderPages = new ArrayDeque<>();
        private BoxFetcher.BoxItem item;
        private String lastContentPath;
        private boolean returnNullFolderPage;
        private boolean returnNullContentBytes;
        private boolean throwItemStatus;
        private boolean throwItemIo;
        private boolean throwFolderStatus;
        private boolean throwFolderIo;

        @Override
        BoxFetcher.BoxItem fetchBoxItemNode(BoxReference ref)
                throws IOException {
            if (throwItemStatus) {
                throw new BoxFetcher.BoxHttpStatusException(500,
                        "bad");
            }
            if (throwItemIo) {
                throw new IOException("boom-item");
            }
            return item;
        }

        @Override
        JsonNode fetchFolderItemsNode(BoxReference ref, int offset)
                throws IOException {
            if (returnNullFolderPage) {
                return null;
            }
            if (throwFolderStatus) {
                throw new BoxFetcher.BoxHttpStatusException(500,
                        "bad");
            }
            if (throwFolderIo) {
                throw new IOException("boom-folder");
            }
            return folderPages.pollFirst();
        }

        @Override
        byte[] fetchContentBytes(String path) throws IOException {
            lastContentPath = path;
            if (returnNullContentBytes) {
                return null;
            }
            return "payload".getBytes(StandardCharsets.UTF_8);
        }
    }
}
