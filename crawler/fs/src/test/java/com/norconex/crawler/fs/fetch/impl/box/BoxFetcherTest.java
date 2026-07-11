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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
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
                new FolderPathsFetchRequest(new Doc("box://ent-01/folders/0")));

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

    private static class StubBoxFetcher extends BoxFetcher {
        private final Deque<JsonNode> folderPages = new ArrayDeque<>();
        private BoxFetcher.BoxItem item;
        private String lastContentPath;
        private boolean returnNullFolderPage;

        @Override
        BoxFetcher.BoxItem fetchBoxItemNode(BoxReference ref) {
            return item;
        }

        @Override
        JsonNode fetchFolderItemsNode(BoxReference ref, int offset) {
            if (returnNullFolderPage) {
                return null;
            }
            return folderPages.pollFirst();
        }

        @Override
        byte[] fetchContentBytes(String path) throws IOException {
            lastContentPath = path;
            return "payload".getBytes(StandardCharsets.UTF_8);
        }
    }
}
