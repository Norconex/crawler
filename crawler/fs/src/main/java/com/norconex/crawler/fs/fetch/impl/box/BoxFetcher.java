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

import static com.norconex.crawler.core.doc.CrawlerDocMetaConstants.PREFIX;
import static com.norconex.crawler.fs.fetch.impl.FetcherSupport.decodeUtf8ErrorBody;
import static com.norconex.crawler.fs.fetch.impl.FetcherSupport.setIsoTimestamp;
import static com.norconex.crawler.fs.fetch.impl.FetcherSupport.urlEncode;
import static com.norconex.crawler.fs.fetch.impl.FileFetchUtil.referenceStartsWith;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.norconex.crawler.core.fetch.AbstractFetcher;
import com.norconex.crawler.core.fetch.FetchDirective;
import com.norconex.crawler.core.fetch.FetchException;
import com.norconex.crawler.core.fetch.FetchRequest;
import com.norconex.crawler.core.fetch.FetchResponse;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.fs.doc.FsDocMetadata;
import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.crawler.fs.fetch.FolderPathsFetchRequest;
import com.norconex.crawler.fs.fetch.FsPath;
import com.norconex.crawler.fs.fetch.impl.GenericFileFetchResponse;
import com.norconex.crawler.fs.fetch.impl.GenericFolderPathsFetchResponse;
import com.norconex.importer.doc.Doc;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

/**
 * Box API fetcher.
 */
@ToString
@EqualsAndHashCode
public class BoxFetcher extends AbstractFetcher<BoxFetcherConfig> {

    private static final String META_PREFIX = PREFIX + "box.";

    @Getter
    private final BoxFetcherConfig configuration = new BoxFetcherConfig();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final ObjectMapper objectMapper = new ObjectMapper();

    record BoxItem(JsonNode node, boolean file, boolean folder) {
    }

    @Override
    protected boolean acceptRequest(@NonNull FetchRequest fetchRequest) {
        return referenceStartsWith(fetchRequest, "box://");
    }

    @Override
    public FetchResponse fetch(FetchRequest fetchRequest)
            throws FetchException {
        if (fetchRequest instanceof FileFetchRequest fileReq) {
            return fetchFile(fileReq);
        }
        return fetchChildPaths((FolderPathsFetchRequest) fetchRequest);
    }

    private FetchResponse fetchFile(FileFetchRequest req)
            throws FetchException {
        var doc = req.getDoc();
        var ref = BoxReference.parse(doc.getReference());
        if (ref.kind() != BoxReference.Kind.ITEM) {
            return GenericFileFetchResponse.builder()
                    .processingOutcome(ProcessingOutcome.NEW)
                    .file(false)
                    .folder(true)
                    .build();
        }

        try {
            var item = fetchBoxItemNode(ref);
            if (item == null) {
                return GenericFileFetchResponse.builder()
                        .processingOutcome(ProcessingOutcome.NOT_FOUND)
                        .build();
            }

            if (item.file()
                    && FetchDirective.DOCUMENT.is(req.getFetchDirective())) {
                fetchContent(doc, ref);
            }
            fetchMetadata(doc, item);

            return GenericFileFetchResponse.builder()
                    .processingOutcome(ProcessingOutcome.NEW)
                    .file(item.file())
                    .folder(item.folder())
                    .build();
        } catch (BoxHttpStatusException e) {
            return GenericFileFetchResponse.builder()
                    .processingOutcome(ProcessingOutcome.BAD_STATUS)
                    .build();
        } catch (IOException e) {
            throw new FetchException(
                    "Could not fetch Box reference: " + doc.getReference(),
                    e);
        }
    }

    private FetchResponse fetchChildPaths(FolderPathsFetchRequest req)
            throws FetchException {
        var ref = BoxReference.parse(req.getDoc().getReference());
        try {
            var childPaths = new LinkedHashSet<FsPath>();
            var offset = 0;
            var hasMore = true;
            while (hasMore) {
                var node = fetchFolderItemsNode(ref, offset);
                if (node == null) {
                    return GenericFolderPathsFetchResponse.builder()
                            .processingOutcome(ProcessingOutcome.NOT_FOUND)
                            .build();
                }

                var entries = node.path("entries");
                if (entries.isArray()) {
                    for (JsonNode entry : entries) {
                        var id = StringUtils
                                .trimToNull(entry.path("id").asText(null));
                        if (id == null) {
                            continue;
                        }
                        var fsPath = new FsPath(ref.child(id).toReference());
                        var type = StringUtils
                                .trimToEmpty(entry.path("type").asText(null));
                        fsPath.setFile("file".equals(type));
                        fsPath.setFolder("folder".equals(type));
                        childPaths.add(fsPath);
                    }
                }

                var limit = node.path("limit").asInt(0);
                var totalCount = node.path("total_count").asInt(0);
                offset += limit;
                hasMore = limit > 0 && offset < totalCount;
            }

            return GenericFolderPathsFetchResponse.builder()
                    .processingOutcome(ProcessingOutcome.NEW)
                    .childPaths(childPaths)
                    .build();
        } catch (BoxHttpStatusException e) {
            return GenericFolderPathsFetchResponse.builder()
                    .processingOutcome(ProcessingOutcome.BAD_STATUS)
                    .childPaths(Set.of())
                    .build();
        } catch (IOException e) {
            throw new FetchException(
                    "Could not fetch Box child references: "
                            + req.getDoc().getReference(),
                    e);
        }
    }

    void fetchContent(Doc doc, BoxReference ref) throws IOException {
        var bytes = fetchContentBytes(ref.itemFileApiPath() + "/content");
        if (bytes != null) {
            doc.setInputStream(new ByteArrayInputStream(bytes));
        }
    }

    void fetchMetadata(Doc doc, BoxItem item) {
        var node = item.node();
        var meta = doc.getMetadata();
        var id = node.path("id").asText(null);
        if (StringUtils.isNotBlank(id)) {
            meta.set(META_PREFIX + "id", id);
        }

        var type = node.path("type").asText(null);
        if (StringUtils.isNotBlank(type)) {
            meta.set(META_PREFIX + "type", type);
        }

        var name = node.path("name").asText(null);
        if (StringUtils.isNotBlank(name)) {
            meta.set(META_PREFIX + "name", name);
        }

        var parentId = node.path("parent").path("id").asText(null);
        if (StringUtils.isNotBlank(parentId)) {
            meta.set(META_PREFIX + "parentId", parentId);
        }

        var etag = node.path("etag").asText(null);
        if (StringUtils.isNotBlank(etag)) {
            meta.set(META_PREFIX + "etag", etag);
        }

        var sha1 = node.path("sha1").asText(null);
        if (StringUtils.isNotBlank(sha1)) {
            meta.set(META_PREFIX + "sha1", sha1);
        }

        var size = node.path("size").asLong(-1);
        if (size >= 0) {
            meta.set(FsDocMetadata.FILE_SIZE, size);
        }

        setTimestamp(meta, META_PREFIX + "created",
                node.path("created_at").asText(null));
        setTimestamp(meta, FsDocMetadata.LAST_MODIFIED,
                node.path("modified_at").asText(null));
    }

    private void setTimestamp(
            com.norconex.commons.lang.map.Properties meta,
            String field,
            String value) {
        setIsoTimestamp(meta, META_PREFIX, field, value);
    }

    BoxItem fetchBoxItemNode(BoxReference ref) throws IOException {
        var fields =
                "id,type,name,parent,etag,sha1,size,created_at,modified_at";
        var file = fetchJson(
                ref.itemFileApiPath() + "?fields=" + urlEncode(fields));
        if (file != null) {
            return new BoxItem(file, true, false);
        }

        var folder = fetchJson(
                ref.itemFolderApiPath() + "?fields=" + urlEncode(fields));
        if (folder != null) {
            return new BoxItem(folder, false, true);
        }
        return null;
    }

    JsonNode fetchFolderItemsNode(BoxReference ref, int offset)
            throws IOException {
        var fields =
                "id,type,name,parent,etag,sha1,size,created_at,modified_at";
        var path = ref.containerFolderApiPath()
                + "/items?fields=" + urlEncode(fields)
                + "&limit=1000&offset=" + offset;
        return fetchJson(path);
    }

    byte[] fetchContentBytes(String path) throws IOException {
        var request = HttpRequest.newBuilder()
                .uri(buildUri(path))
                .header("Authorization",
                        "Bearer " + configuration.getAccessToken())
                .GET()
                .build();

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling Box API", e);
        }

        var status = response.statusCode();
        if (configuration.getValidStatusCodes().contains(status)) {
            return response.body();
        }
        if (configuration.getNotFoundStatusCodes().contains(status)) {
            return null;
        }
        throw new BoxHttpStatusException(status,
                decodeUtf8ErrorBody(response.body()));
    }

    JsonNode fetchJson(String path) throws IOException {
        var request = HttpRequest.newBuilder()
                .uri(buildUri(path))
                .header("Authorization",
                        "Bearer " + configuration.getAccessToken())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling Box API", e);
        }

        var status = response.statusCode();
        if (configuration.getValidStatusCodes().contains(status)) {
            return objectMapper.readTree(response.body());
        }
        if (configuration.getNotFoundStatusCodes().contains(status)) {
            return null;
        }
        throw new BoxHttpStatusException(status,
                StringUtils.abbreviate(response.body(), 512));
    }

    private URI buildUri(String pathOrUrl) {
        if (StringUtils.startsWithIgnoreCase(pathOrUrl, "http://")
                || StringUtils.startsWithIgnoreCase(pathOrUrl, "https://")) {
            return URI.create(pathOrUrl);
        }
        return URI.create(StringUtils.removeEnd(configuration.getApiBaseUrl(),
                "/")
                + (pathOrUrl.startsWith("/") ? pathOrUrl : "/" + pathOrUrl));
    }

    static final class BoxHttpStatusException extends IOException {

        private static final long serialVersionUID = 1L;

        @Getter
        private final int statusCode;

        BoxHttpStatusException(int statusCode, String message) {
            super("Box API returned status %d: %s".formatted(statusCode,
                    message));
            this.statusCode = statusCode;
        }
    }
}
