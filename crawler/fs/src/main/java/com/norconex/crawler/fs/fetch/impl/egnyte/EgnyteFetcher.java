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

import static com.norconex.crawler.core.doc.CrawlerDocMetaConstants.PREFIX;
import static com.norconex.crawler.fs.fetch.impl.FileFetchUtil.referenceStartsWith;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.norconex.crawler.core.CrawlerConfig.ChangeDiscovery;
import com.norconex.crawler.core.CrawlerException;
import com.norconex.crawler.core.fetch.AbstractFetcher;
import com.norconex.crawler.core.fetch.FetchDirective;
import com.norconex.crawler.core.fetch.FetchException;
import com.norconex.crawler.core.fetch.FetchRequest;
import com.norconex.crawler.core.fetch.FetchResponse;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.core.session.CrawlerAttributes;
import com.norconex.crawler.core.session.CrawlerSession;
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
 * Egnyte API fetcher.
 */
@ToString
@EqualsAndHashCode
public class EgnyteFetcher extends AbstractFetcher<EgnyteFetcherConfig> {

    private static final String META_PREFIX = PREFIX + "egnyte.";
    private static final String DELTA_CURSOR_KEY_PREFIX =
            "egnyte.delta.cursor.";
    private static final int DELTA_MAX_PAGES = 1000;
    private static final Set<Integer> DELTA_CURSOR_RESET_STATUSES = Set.of(
            400,
            404,
            410);

    @Getter
    private final EgnyteFetcherConfig configuration =
            new EgnyteFetcherConfig();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final ObjectMapper objectMapper = new ObjectMapper();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private transient CrawlerAttributes sessionAttributes;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private transient boolean sourceDeltaEnabled;

    record EgnyteItem(JsonNode node, boolean file, boolean folder) {
    }

    @Override
    protected void fetcherStartup(CrawlerSession crawler) {
        sessionAttributes = crawler.getSessionAttributes();
        sourceDeltaEnabled = crawler.isIncremental()
                && crawler.getCrawlContext().getCrawlConfig()
                        .getChangeDiscovery() == ChangeDiscovery.SOURCE_DELTA;
        if (sourceDeltaEnabled) {
            validateConfiguredSourceDeltaRoots(crawler);
        }
    }

    @Override
    protected void fetcherShutdown(CrawlerSession crawler) {
        sourceDeltaEnabled = false;
        sessionAttributes = null;
    }

    @Override
    protected boolean acceptRequest(@NonNull FetchRequest fetchRequest) {
        return referenceStartsWith(fetchRequest, "egnyte://");
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
        var ref = EgnyteReference.parse(doc.getReference());
        if (ref.kind() != EgnyteReference.Kind.ITEM) {
            return GenericFileFetchResponse.builder()
                    .processingOutcome(ProcessingOutcome.NEW)
                    .file(false)
                    .folder(true)
                    .build();
        }

        try {
            var item = fetchEgnyteItemNode(ref);
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
        } catch (EgnyteHttpStatusException e) {
            return GenericFileFetchResponse.builder()
                    .processingOutcome(ProcessingOutcome.BAD_STATUS)
                    .build();
        } catch (IOException e) {
            throw new FetchException(
                    "Could not fetch Egnyte reference: " + doc.getReference(),
                    e);
        }
    }

    private FetchResponse fetchChildPaths(FolderPathsFetchRequest req)
            throws FetchException {
        var ref = EgnyteReference.parse(req.getDoc().getReference());
        try {
            if (isSourceDeltaEnabled() && ref.isDiscoveryEntry()) {
                return fetchDeltaChildPaths(ref);
            }

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
                        var id = StringUtils.trimToNull(
                                entry.path("id").asText(null));
                        if (id == null) {
                            continue;
                        }
                        var fsPath = new FsPath(ref.child(id).toReference());
                        var isFile = entry.path("is_folder").isBoolean()
                                ? !entry.path("is_folder").asBoolean()
                                : "file".equals(StringUtils.trimToEmpty(
                                        entry.path("type").asText(null)));
                        var isFolder = entry.path("is_folder").isBoolean()
                                ? entry.path("is_folder").asBoolean()
                                : "folder".equals(StringUtils.trimToEmpty(
                                        entry.path("type").asText(null)));
                        fsPath.setFile(isFile);
                        fsPath.setFolder(isFolder);
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
        } catch (EgnyteHttpStatusException e) {
            return GenericFolderPathsFetchResponse.builder()
                    .processingOutcome(ProcessingOutcome.BAD_STATUS)
                    .childPaths(Set.of())
                    .build();
        } catch (IOException e) {
            throw new FetchException(
                    "Could not fetch Egnyte child references: "
                            + req.getDoc().getReference(),
                    e);
        }
    }

    private FetchResponse fetchDeltaChildPaths(EgnyteReference ref)
            throws IOException {
        var storedCursor = getDeltaCursor(ref);
        var usingStoredCursor = storedCursor.isPresent();
        var cursor = storedCursor.orElseGet(() -> "");
        if (!usingStoredCursor) {
            cursor = fetchStartCursor(ref);
        }

        while (true) {
            try {
                return fetchDeltaPages(ref, cursor);
            } catch (EgnyteHttpStatusException e) {
                if (usingStoredCursor
                        && DELTA_CURSOR_RESET_STATUSES.contains(
                                e.getStatusCode())) {
                    clearDeltaCursor(ref);
                    cursor = fetchStartCursor(ref);
                    usingStoredCursor = false;
                    continue;
                }
                throw e;
            }
        }
    }

    private FetchResponse fetchDeltaPages(EgnyteReference ref, String cursor)
            throws IOException {
        Set<FsPath> childPaths = new LinkedHashSet<>();
        var pageCount = 0;
        String pageCursor = cursor;
        String newCursor = null;

        while (StringUtils.isNotBlank(pageCursor)) {
            pageCount++;
            if (pageCount > DELTA_MAX_PAGES) {
                throw new IOException(
                        "Exceeded maximum number of Egnyte "
                                + "changes pages (" + DELTA_MAX_PAGES + ").");
            }

            var page = fetchChangesNode(ref, pageCursor);
            if (page == null) {
                return notFoundFolderPathsResponse();
            }

            for (JsonNode event : page.path("events")) {
                var childId = firstNonBlank(
                        event.path("item_id").asText(null),
                        event.path("object_id").asText(null),
                        event.path("id").asText(null),
                        event.path("entry").path("id").asText(null));
                if (childId == null) {
                    continue;
                }

                var fsPath = new FsPath(ref.child(childId).toReference());

                var removed = event.path("removed").asBoolean(false)
                        || event.path("deleted").asBoolean(false)
                        || StringUtils.equalsAnyIgnoreCase(
                                event.path("event_type").asText(null),
                                "removed",
                                "deleted")
                        || StringUtils.equalsAnyIgnoreCase(
                                event.path("action").asText(null),
                                "removed",
                                "deleted");
                if (removed) {
                    fsPath.setFile(true);
                    fsPath.setFolder(false);
                } else {
                    var isFolder = event.path("is_folder").isBoolean()
                            ? event.path("is_folder").asBoolean()
                            : "folder".equalsIgnoreCase(
                                    event.path("type").asText(null));
                    fsPath.setFolder(isFolder);
                    fsPath.setFile(!isFolder);
                }
                childPaths.add(fsPath);
            }

            var pageNewCursor = StringUtils.trimToNull(
                    page.path("new_cursor").asText(null));
            if (pageNewCursor != null) {
                newCursor = pageNewCursor;
            }
            pageCursor = StringUtils.trimToNull(
                    page.path("next_cursor").asText(null));
        }

        if (StringUtils.isNotBlank(newCursor)) {
            setDeltaCursor(ref, newCursor);
        }

        return GenericFolderPathsFetchResponse.builder()
                .processingOutcome(ProcessingOutcome.NEW)
                .childPaths(childPaths)
                .build();
    }

    void fetchContent(Doc doc, EgnyteReference ref) throws IOException {
        var bytes = fetchContentBytes(ref, ref.itemFileApiPath() + "/content");
        if (bytes != null) {
            doc.setInputStream(new ByteArrayInputStream(bytes));
        }
    }

    void fetchMetadata(Doc doc, EgnyteItem item) {
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

        var path = node.path("path").asText(null);
        if (StringUtils.isNotBlank(path)) {
            meta.set(FsDocMetadata.PATH, path);
        }

        var checksum = node.path("checksum").asText(null);
        if (StringUtils.isNotBlank(checksum)) {
            meta.set(META_PREFIX + "checksum", checksum);
        }

        var size = node.path("size").asLong(-1);
        if (size >= 0) {
            meta.set(FsDocMetadata.FILE_SIZE, size);
        }

        setTimestamp(meta, META_PREFIX + "created",
                node.path("created_at").asText(null));
        setTimestamp(meta, FsDocMetadata.LAST_MODIFIED,
                node.path("last_modified").asText(null));
    }

    private void setTimestamp(
            com.norconex.commons.lang.map.Properties meta,
            String field,
            String value) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        try {
            meta.set(field,
                    ZonedDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME)
                            .toInstant().toEpochMilli());
        } catch (DateTimeParseException e) {
            meta.set(META_PREFIX + "invalidDate." + field, value);
        }
    }

    EgnyteItem fetchEgnyteItemNode(EgnyteReference ref) throws IOException {
        var fields =
                "id,type,name,path,checksum,size,created_at,last_modified,is_folder";
        var file = fetchJson(ref,
                ref.itemFileApiPath() + "?fields=" + urlEncode(fields));
        if (file != null) {
            return new EgnyteItem(file, true, false);
        }

        var folder = fetchJson(ref,
                ref.itemFolderApiPath() + "?fields=" + urlEncode(fields));
        if (folder != null) {
            return new EgnyteItem(folder, false, true);
        }
        return null;
    }

    JsonNode fetchFolderItemsNode(EgnyteReference ref, int offset)
            throws IOException {
        var fields =
                "id,type,name,path,checksum,size,created_at,last_modified,is_folder";
        var path = ref.containerFolderApiPath()
                + "/children?fields=" + urlEncode(fields)
                + "&limit=1000&offset=" + offset;
        return fetchJson(ref, path);
    }

    JsonNode fetchChangesNode(EgnyteReference ref, String cursor)
            throws IOException {
        var path = "/events?folder_id=" + urlEncode(ref.folderId())
                + "&cursor=" + urlEncode(cursor);
        return fetchJson(ref, path);
    }

    String fetchStartCursor(EgnyteReference ref) throws IOException {
        var page = fetchJson(ref,
                "/events/cursor?folder_id=" + urlEncode(ref.folderId()));
        if (page == null) {
            return null;
        }
        return StringUtils.trimToNull(firstNonBlank(
                page.path("cursor").asText(null),
                page.path("start_cursor").asText(null)));
    }

    byte[] fetchContentBytes(EgnyteReference ref, String path)
            throws IOException {
        var request = HttpRequest.newBuilder()
                .uri(buildUri(ref.domain(), path))
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
            throw new IOException("Interrupted while calling Egnyte API", e);
        }

        var status = response.statusCode();
        if (configuration.getValidStatusCodes().contains(status)) {
            return response.body();
        }
        if (configuration.getNotFoundStatusCodes().contains(status)) {
            return null;
        }
        throw new EgnyteHttpStatusException(status,
                decodeErrorBody(response.body()));
    }

    JsonNode fetchJson(EgnyteReference ref, String path) throws IOException {
        var request = HttpRequest.newBuilder()
                .uri(buildUri(ref.domain(), path))
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
            throw new IOException("Interrupted while calling Egnyte API", e);
        }

        var status = response.statusCode();
        if (configuration.getValidStatusCodes().contains(status)) {
            return objectMapper.readTree(response.body());
        }
        if (configuration.getNotFoundStatusCodes().contains(status)) {
            return null;
        }
        throw new EgnyteHttpStatusException(
                status,
                StringUtils.abbreviate(response.body(), 512));
    }

    private URI buildUri(String domain, String pathOrUrl) {
        if (StringUtils.startsWithIgnoreCase(pathOrUrl, "http://")
                || StringUtils.startsWithIgnoreCase(pathOrUrl, "https://")) {
            return URI.create(pathOrUrl);
        }
        var base = resolveApiBaseUrl(domain);
        return URI.create(StringUtils.removeEnd(base, "/")
                + (pathOrUrl.startsWith("/") ? pathOrUrl : "/" + pathOrUrl));
    }

    private String resolveApiBaseUrl(String domain) {
        return StringUtils.defaultString(configuration.getApiBaseUrl())
                .replace("{domain}", domain);
    }

    private String decodeErrorBody(byte[] body) {
        return StringUtils.abbreviate(
                new String(body == null ? new byte[0] : body,
                        StandardCharsets.UTF_8),
                512);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    boolean isSourceDeltaEnabled() {
        return sourceDeltaEnabled;
    }

    Optional<String> getDeltaCursor(EgnyteReference ref) {
        if (sessionAttributes == null) {
            return Optional.empty();
        }
        return sessionAttributes.getString(deltaCursorKey(ref))
                .flatMap(value -> Optional.ofNullable(
                        StringUtils.trimToNull(value)));
    }

    void setDeltaCursor(EgnyteReference ref, String cursor) {
        if (sessionAttributes != null && StringUtils.isNotBlank(cursor)) {
            sessionAttributes.setString(deltaCursorKey(ref), cursor);
        }
    }

    void clearDeltaCursor(EgnyteReference ref) {
        if (sessionAttributes != null) {
            sessionAttributes.setString(deltaCursorKey(ref), "");
        }
    }

    private void validateConfiguredSourceDeltaRoots(CrawlerSession crawler) {
        for (String startRef : crawler.getCrawlContext().getCrawlConfig()
                .getStartReferences()) {
            if (StringUtils.isBlank(startRef)
                    || !StringUtils.startsWith(startRef, "egnyte://")) {
                continue;
            }
            validateConfiguredSourceDeltaRoot(EgnyteReference.parse(startRef));
        }
    }

    private void validateConfiguredSourceDeltaRoot(EgnyteReference ref) {
        if (ref.isDiscoveryEntry()) {
            return;
        }
        throw new CrawlerException("Unsupported Egnyte SOURCE_DELTA "
                + "start reference: " + ref.toReference()
                + ". Use the Egnyte root folder start reference.");
    }

    private static FetchResponse notFoundFolderPathsResponse() {
        return GenericFolderPathsFetchResponse.builder()
                .processingOutcome(ProcessingOutcome.NOT_FOUND)
                .childPaths(Set.of())
                .build();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            var trimmed = StringUtils.trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private static String deltaCursorKey(EgnyteReference ref) {
        return DELTA_CURSOR_KEY_PREFIX + ref.toReference();
    }

    static final class EgnyteHttpStatusException extends IOException {

        private static final long serialVersionUID = 1L;

        @Getter
        private final int statusCode;

        EgnyteHttpStatusException(int statusCode, String message) {
            super("Egnyte API returned status %d: %s".formatted(statusCode,
                    message));
            this.statusCode = statusCode;
        }
    }
}
