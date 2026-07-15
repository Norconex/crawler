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

import static com.norconex.crawler.core.doc.CrawlerDocMetaConstants.PREFIX;
import static com.norconex.crawler.fs.fetch.impl.FetcherSupport.urlEncode;
import static com.norconex.crawler.fs.fetch.impl.FileFetchUtil.referenceStartsWith;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
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
import com.norconex.importer.doc.DocMetaConstants;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * Google Drive API fetcher for Google Workspace My Drive and Shared Drives.
 */
@Slf4j
@ToString
@EqualsAndHashCode
public class GoogleDriveFetcher
        extends AbstractFetcher<GoogleDriveFetcherConfig> {

    private static final String META_PREFIX = PREFIX + "gdrive.";
    private static final String GOOGLE_NATIVE_MIME_PREFIX =
            "application/vnd.google-apps.";
    private static final String GOOGLE_FOLDER_MIME =
            "application/vnd.google-apps.folder";
    private static final String TOKEN_URL =
            "https://oauth2.googleapis.com/token";
    private static final String DRIVE_READONLY_SCOPE =
            "https://www.googleapis.com/auth/drive.readonly";
    private static final String DELTA_CURSOR_KEY_PREFIX =
            "googledrive.delta.cursor.";
    private static final int DELTA_MAX_PAGES = 1000;
    private static final Set<Integer> DELTA_CURSOR_RESET_STATUSES = Set.of(
            400,
            404,
            410);

    @Getter
    private final GoogleDriveFetcherConfig configuration =
            new GoogleDriveFetcherConfig();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final ObjectMapper objectMapper = new ObjectMapper();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private String accessToken;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private Instant accessTokenExpiry = Instant.EPOCH;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private transient CrawlerAttributes sessionAttributes;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private transient boolean sourceDeltaEnabled;

    private record ContentPlan(
            String contentPath,
            String sourceMimeType,
            String deliveredContentType,
            String exportMimeType) {
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
        return referenceStartsWith(fetchRequest, "gdrive://");
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
        var ref = GoogleDriveReference.parse(doc.getReference());
        if (ref.kind() != GoogleDriveReference.Kind.ITEM) {
            return GenericFileFetchResponse.builder()
                    .processingOutcome(ProcessingOutcome.NEW)
                    .file(false)
                    .folder(true)
                    .build();
        }
        try {
            var item = fetchItemNode(ref);
            if (item == null) {
                return GenericFileFetchResponse.builder()
                        .processingOutcome(ProcessingOutcome.NOT_FOUND)
                        .build();
            }
            var mimeType = item.path("mimeType").asText(null);
            var isFolder = StringUtils.equals(mimeType, GOOGLE_FOLDER_MIME);
            var isFile = !isFolder;
            var contentPlan = isFile ? resolveContentPlan(ref, item) : null;

            if (isFile && FetchDirective.DOCUMENT.is(req.getFetchDirective())) {
                fetchContent(doc, ref, item, contentPlan);
            }
            fetchMetadata(doc, item, contentPlan);

            return GenericFileFetchResponse.builder()
                    .processingOutcome(ProcessingOutcome.NEW)
                    .file(isFile)
                    .folder(isFolder)
                    .build();
        } catch (GoogleDriveHttpStatusException e) {
            return GenericFileFetchResponse.builder()
                    .processingOutcome(ProcessingOutcome.BAD_STATUS)
                    .build();
        } catch (IOException e) {
            throw new FetchException(
                    "Could not fetch Google Drive reference: "
                            + doc.getReference(),
                    e);
        }
    }

    private FetchResponse fetchChildPaths(FolderPathsFetchRequest req)
            throws FetchException {
        var ref = GoogleDriveReference.parse(req.getDoc().getReference());
        try {
            if (isSourceDeltaEnabled() && ref.isDiscoveryEntry()) {
                return fetchDeltaChildPaths(ref);
            }
            var childPaths = new LinkedHashSet<FsPath>();
            String pageToken = null;
            do {
                var node = fetchChildrenNode(ref, pageToken);
                if (node == null) {
                    return GenericFolderPathsFetchResponse.builder()
                            .processingOutcome(ProcessingOutcome.NOT_FOUND)
                            .build();
                }
                var files = node.path("files");
                if (files.isArray()) {
                    for (JsonNode fileNode : files) {
                        var childId = fileNode.path("id").asText(null);
                        if (StringUtils.isBlank(childId)) {
                            continue;
                        }
                        childPaths.add(new FsPath(ref.child(childId)
                                .toReference()));
                    }
                }
                pageToken = StringUtils.trimToNull(
                        node.path("nextPageToken").asText(null));
            } while (pageToken != null);

            return GenericFolderPathsFetchResponse.builder()
                    .processingOutcome(ProcessingOutcome.NEW)
                    .childPaths(childPaths)
                    .build();
        } catch (GoogleDriveHttpStatusException e) {
            return GenericFolderPathsFetchResponse.builder()
                    .processingOutcome(ProcessingOutcome.BAD_STATUS)
                    .build();
        } catch (IOException e) {
            throw new FetchException(
                    "Could not fetch Google Drive child references: "
                            + req.getDoc().getReference(),
                    e);
        }
    }

    private FetchResponse fetchDeltaChildPaths(GoogleDriveReference ref)
            throws IOException {
        var storedCursor = getDeltaCursor(ref);
        var usingStoredCursor = storedCursor.isPresent();
        var pageToken = storedCursor.orElseGet(() -> "");
        if (!usingStoredCursor) {
            pageToken = fetchStartPageToken(ref);
        }

        while (true) {
            try {
                return fetchDeltaPages(ref, pageToken);
            } catch (GoogleDriveHttpStatusException e) {
                if (usingStoredCursor
                        && DELTA_CURSOR_RESET_STATUSES.contains(
                                e.getStatusCode())) {
                    clearDeltaCursor(ref);
                    pageToken = fetchStartPageToken(ref);
                    usingStoredCursor = false;
                    continue;
                }
                throw e;
            }
        }
    }

    private FetchResponse fetchDeltaPages(
            GoogleDriveReference ref,
            String firstPageToken)
            throws IOException {
        Set<FsPath> childPaths = new LinkedHashSet<>();
        String pageToken = firstPageToken;
        String newStartPageToken = null;
        int pageCount = 0;

        while (StringUtils.isNotBlank(pageToken)) {
            pageCount++;
            if (pageCount > DELTA_MAX_PAGES) {
                throw new IOException(
                        "Exceeded maximum number of Google Drive "
                                + "changes pages (" + DELTA_MAX_PAGES + ").");
            }

            var page = fetchChangesNode(ref, pageToken);
            if (page == null) {
                return notFoundFolderPathsResponse();
            }

            for (JsonNode change : page.path("changes")) {
                var childId = StringUtils.trimToNull(
                        change.path("fileId").asText(null));
                if (childId == null) {
                    childId = StringUtils.trimToNull(
                            change.path("file").path("id").asText(null));
                }
                if (childId == null) {
                    continue;
                }

                var fsPath = new FsPath(ref.child(childId).toReference());
                if (change.path("removed").asBoolean(false)) {
                    fsPath.setFile(true);
                    fsPath.setFolder(false);
                } else {
                    var file = change.path("file");
                    var mimeType = file.path("mimeType").asText(null);
                    var isFolder = StringUtils.equals(
                            mimeType, GOOGLE_FOLDER_MIME);
                    fsPath.setFolder(isFolder);
                    fsPath.setFile(!isFolder);
                }
                childPaths.add(fsPath);
            }

            pageToken = StringUtils.trimToNull(
                    page.path("nextPageToken").asText(null));
            var currentStartPageToken = StringUtils.trimToNull(
                    page.path("newStartPageToken").asText(null));
            if (currentStartPageToken != null) {
                newStartPageToken = currentStartPageToken;
            }
        }

        if (StringUtils.isNotBlank(newStartPageToken)) {
            setDeltaCursor(ref, newStartPageToken);
        }

        return GenericFolderPathsFetchResponse.builder()
                .processingOutcome(ProcessingOutcome.NEW)
                .childPaths(childPaths)
                .build();
    }

    void fetchContent(Doc doc, GoogleDriveReference ref, JsonNode item)
            throws IOException {
        fetchContent(doc, ref, item, resolveContentPlan(ref, item));
    }

    void fetchContent(Doc doc, GoogleDriveReference ref, JsonNode item,
            ContentPlan contentPlan)
            throws IOException {
        var contentPath =
                contentPlan == null ? null : contentPlan.contentPath();
        if (contentPath == null) {
            var sourceMime = contentPlan != null
                    ? contentPlan.sourceMimeType()
                    : item.path("mimeType").asText(null);
            doc.getMetadata().set(
                    META_PREFIX + "content.status",
                    "unsupported-google-native-mime");
            doc.getMetadata().set(
                    META_PREFIX + "content.sourceMimeType",
                    sourceMime);
            LOG.warn(
                    "Google Drive file {} has unsupported Google-native "
                            + "mime type without export mapping: {}",
                    ref.toReference(),
                    sourceMime);
            return;
        }

        var bytes = fetchContentBytes(contentPath);
        try (var is = doc.getStreamFactory().newInputStream(
                new ByteArrayInputStream(bytes))) {
            is.enforceFullCaching();
            doc.setInputStream(is);
        }
    }

    ContentPlan resolveContentPlan(GoogleDriveReference ref, JsonNode item) {
        var sourceMime = item.path("mimeType").asText(null);
        if (StringUtils.isBlank(sourceMime)) {
            return new ContentPlan(
                    ref.itemApiPath() + "?alt=media",
                    null,
                    null,
                    null);
        }
        if (!sourceMime.startsWith(GOOGLE_NATIVE_MIME_PREFIX)
                || StringUtils.equals(sourceMime, GOOGLE_FOLDER_MIME)) {
            return new ContentPlan(
                    ref.itemApiPath() + "?alt=media",
                    sourceMime,
                    sourceMime,
                    null);
        }
        return resolveExportMimeType(sourceMime)
                .map(exportMime -> new ContentPlan(
                        ref.itemApiPath() + "/export?mimeType="
                                + urlEncode(exportMime),
                        sourceMime,
                        exportMime,
                        exportMime))
                .orElseGet(() -> new ContentPlan(
                        null,
                        sourceMime,
                        sourceMime,
                        null));
    }

    Optional<String> resolveExportMimeType(String sourceMimeType) {
        var configured = configuration.getExportMimeTypeMap();
        if (configured.containsKey(sourceMimeType)) {
            return Optional.ofNullable(
                    StringUtils.trimToNull(configured.get(sourceMimeType)));
        }
        return Optional.ofNullable(
                configuration.defaultExportMimeTypes().get(sourceMimeType));
    }

    JsonNode fetchItemNode(GoogleDriveReference ref) throws IOException {
        var response = executeGet(ref.itemApiPath() + "?fields="
                + urlEncode("id,name,mimeType,modifiedTime,createdTime,size,"
                        + "parents,trashed,md5Checksum,sha1Checksum,"
                        + "sha256Checksum,webViewLink,webContentLink"));
        if (response.statusCode() == 404) {
            return null;
        }
        ensureStatus(response, "read file");
        return objectMapper.readTree(response.body());
    }

    JsonNode fetchChildrenNode(GoogleDriveReference ref, String pageToken)
            throws IOException {
        var parentId = ref.kind() == GoogleDriveReference.Kind.ITEM
                ? ref.itemId()
                : "root";
        var query = "'" + parentId + "' in parents and trashed=false";
        var path = "/drive/v3/files?fields="
                + urlEncode("nextPageToken,files(id,mimeType)")
                + "&q=" + urlEncode(query)
                + "&supportsAllDrives=true&includeItemsFromAllDrives=true";
        if (ref.driveId() != null) {
            path += "&corpora=drive&driveId=" + urlEncode(ref.driveId());
        }
        if (pageToken != null) {
            path += "&pageToken=" + urlEncode(pageToken);
        }
        var response = executeGet(path);
        if (response.statusCode() == 404) {
            return null;
        }
        ensureStatus(response, "read children");
        return objectMapper.readTree(response.body());
    }

    JsonNode fetchChangesNode(GoogleDriveReference ref, String pageToken)
            throws IOException {
        var path = "/drive/v3/changes?pageToken=" + urlEncode(pageToken)
                + "&fields="
                + urlEncode(
                        "nextPageToken,newStartPageToken,changes(fileId,removed,file(id,mimeType))")
                + "&supportsAllDrives=true&includeItemsFromAllDrives=true";
        if (ref.driveId() != null) {
            path += "&driveId=" + urlEncode(ref.driveId())
                    + "&corpora=drive";
        } else if (ref.userId() != null) {
            path += "&restrictToMyDrive=true";
        }
        var response = executeGet(path);
        if (response.statusCode() == 404) {
            return null;
        }
        ensureStatus(response, "read changes");
        return objectMapper.readTree(response.body());
    }

    String fetchStartPageToken(GoogleDriveReference ref) throws IOException {
        var path = "/drive/v3/changes/startPageToken?supportsAllDrives=true";
        if (ref.driveId() != null) {
            path += "&driveId=" + urlEncode(ref.driveId());
        }
        var response = executeGet(path);
        ensureStatus(response, "read start page token");
        return StringUtils.trimToNull(
                objectMapper.readTree(response.body())
                        .path("startPageToken").asText(null));
    }

    byte[] fetchContentBytes(String pathOrUrl) throws IOException {
        var response = executeGet(pathOrUrl);
        ensureStatus(response, "download content");
        return response.body();
    }

    private static FetchResponse notFoundFolderPathsResponse() {
        return GenericFolderPathsFetchResponse.builder()
                .processingOutcome(ProcessingOutcome.NOT_FOUND)
                .childPaths(Set.of())
                .build();
    }

    private HttpResponse<byte[]> executeGet(String pathOrUrl)
            throws IOException {
        var url = StringUtils.startsWithAny(pathOrUrl, "http://", "https://")
                ? pathOrUrl
                : "https://www.googleapis.com" + pathOrUrl;
        var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + getAccessToken())
                .GET()
                .build();
        return sendRequest(request);
    }

    HttpResponse<byte[]> sendRequest(HttpRequest request) throws IOException {
        try {
            return httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling Google Drive.",
                    e);
        }
    }

    private synchronized String getAccessToken() throws IOException {
        if (StringUtils.isNotBlank(accessToken)
                && Instant.now().isBefore(accessTokenExpiry.minusSeconds(30))) {
            return accessToken;
        }

        var cfg = configuration;
        if (StringUtils.isAnyBlank(cfg.getClientEmail(), cfg.getPrivateKey())) {
            throw new IOException(
                    "Google Drive service-account auth requires clientEmail "
                            + "and privateKey.");
        }

        var now = Instant.now();
        var jwt = createJwtAssertion(
                cfg.getClientEmail(),
                cfg.getDelegatedUser(),
                cfg.getPrivateKey(),
                now,
                now.plusSeconds(3600));

        var body = "grant_type=" + urlEncode(
                "urn:ietf:params:oauth:grant-type:jwt-bearer")
                + "&assertion=" + urlEncode(jwt);

        var request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Content-Type",
                        "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var response = sendRequest(request);
        ensureStatus(response, "acquire access token");

        var json = objectMapper.readTree(response.body());
        accessToken = json.path("access_token").asText(null);
        if (StringUtils.isBlank(accessToken)) {
            throw new IOException("OAuth token response missing access_token.");
        }
        var expiresIn = Math.max(60L, json.path("expires_in").asLong(3600));
        accessTokenExpiry = Instant.now().plusSeconds(expiresIn);
        return accessToken;
    }

    private void fetchMetadata(Doc doc, JsonNode item,
            ContentPlan contentPlan) {
        var meta = doc.getMetadata();
        setMetaIfPresent(meta, META_PREFIX + "id",
                item.path("id").asText(null));
        setMetaIfPresent(meta, META_PREFIX + "name",
                item.path("name").asText(null));
        setMetaIfPresent(meta, META_PREFIX + "mimeType",
                item.path("mimeType").asText(null));
        setMetaIfPresent(meta, META_PREFIX + "webViewLink",
                item.path("webViewLink").asText(null));
        setMetaIfPresent(meta, META_PREFIX + "webContentLink",
                item.path("webContentLink").asText(null));
        setMetaIfPresent(meta, META_PREFIX + "md5Checksum",
                item.path("md5Checksum").asText(null));
        setMetaIfPresent(meta, META_PREFIX + "sha1Checksum",
                item.path("sha1Checksum").asText(null));
        setMetaIfPresent(meta, META_PREFIX + "sha256Checksum",
                item.path("sha256Checksum").asText(null));

        var mimeType = contentPlan != null
                ? StringUtils.defaultIfBlank(
                        contentPlan.deliveredContentType(),
                        item.path("mimeType").asText(null))
                : item.path("mimeType").asText(null);
        if (StringUtils.isNotBlank(mimeType)) {
            meta.set(DocMetaConstants.CONTENT_TYPE, mimeType);
        }

        if (contentPlan != null) {
            setMetaIfPresent(meta, META_PREFIX + "sourceMimeType",
                    contentPlan.sourceMimeType());
            setMetaIfPresent(meta, META_PREFIX + "content.exportMimeType",
                    contentPlan.exportMimeType());
            if (StringUtils.isNotBlank(contentPlan.exportMimeType())) {
                meta.set(META_PREFIX + "content.status", "exported");
            }
        }

        var size = item.path("size").asText(null);
        if (StringUtils.isNumeric(size)) {
            meta.set(FsDocMetadata.FILE_SIZE, size);
        }

        setTimestamp(meta, FsDocMetadata.LAST_MODIFIED,
                item.path("modifiedTime").asText(null));
        setTimestamp(meta, META_PREFIX + "createdTime",
                item.path("createdTime").asText(null));

        var parents = item.path("parents");
        if (parents.isArray() && !parents.isEmpty()) {
            Set<String> parentIds = new LinkedHashSet<>();
            for (JsonNode parent : parents) {
                var parentId = parent.asText(null);
                if (StringUtils.isNotBlank(parentId)) {
                    parentIds.add(parentId);
                }
            }
            if (!parentIds.isEmpty()) {
                meta.set(META_PREFIX + "parentIds",
                        parentIds.toArray(String[]::new));
            }
        }
    }

    private static void setMetaIfPresent(
            com.norconex.commons.lang.map.Properties meta,
            String key,
            String value) {
        if (StringUtils.isNotBlank(value)) {
            meta.set(key, value);
        }
    }

    private static void setTimestamp(
            com.norconex.commons.lang.map.Properties meta,
            String key,
            String value) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        try {
            meta.set(key, ZonedDateTime.parse(value,
                    DateTimeFormatter.ISO_DATE_TIME).toInstant()
                    .toEpochMilli());
        } catch (RuntimeException e) {
            // Keep crawl resilient to source-specific timestamp oddities.
            meta.set(key, value);
        }
    }

    private static String createJwtAssertion(
            String clientEmail,
            String delegatedUser,
            String privateKey,
            Instant issuedAt,
            Instant expiresAt)
            throws IOException {
        var header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        var claims = new StringBuilder()
                .append("{\"iss\":\"").append(jsonEscape(clientEmail))
                .append("\",\"scope\":\"")
                .append(jsonEscape(DRIVE_READONLY_SCOPE))
                .append("\",\"aud\":\"")
                .append(jsonEscape(TOKEN_URL))
                .append("\",\"iat\":").append(issuedAt.getEpochSecond())
                .append(",\"exp\":").append(expiresAt.getEpochSecond());
        if (StringUtils.isNotBlank(delegatedUser)) {
            claims.append(",\"sub\":\"")
                    .append(jsonEscape(delegatedUser))
                    .append('"');
        }
        claims.append('}');

        var encodedHeader = base64Url(header.getBytes(StandardCharsets.UTF_8));
        var encodedClaims =
                base64Url(claims.toString().getBytes(StandardCharsets.UTF_8));
        var signingInput = encodedHeader + "." + encodedClaims;
        var signature = signRs256(
                signingInput.getBytes(StandardCharsets.UTF_8),
                parsePrivateKey(privateKey));
        return signingInput + "." + base64Url(signature);
    }

    private static PrivateKey parsePrivateKey(String pem) throws IOException {
        var sanitized = StringUtils.remove(pem,
                "-----BEGIN PRIVATE KEY-----");
        sanitized = StringUtils.remove(sanitized,
                "-----END PRIVATE KEY-----");
        sanitized = StringUtils.deleteWhitespace(sanitized);
        try {
            var keyBytes = Base64.getDecoder().decode(sanitized);
            var spec = new PKCS8EncodedKeySpec(keyBytes);
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("Invalid RSA private key format.", e);
        }
    }

    private static byte[] signRs256(byte[] payload, PrivateKey privateKey)
            throws IOException {
        try {
            var signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(payload);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not sign JWT assertion.", e);
        }
    }

    private static String jsonEscape(String value) {
        return StringUtils.defaultString(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void ensureStatus(HttpResponse<byte[]> response,
            String action) throws IOException {
        var status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        throw new GoogleDriveHttpStatusException(
                status,
                "Google Drive failed to " + action);
    }

    boolean isSourceDeltaEnabled() {
        return sourceDeltaEnabled;
    }

    Optional<String> getDeltaCursor(GoogleDriveReference ref) {
        if (sessionAttributes == null) {
            return Optional.empty();
        }
        return sessionAttributes.getString(deltaCursorKey(ref))
                .flatMap(value -> Optional.ofNullable(
                        StringUtils.trimToNull(value)));
    }

    void setDeltaCursor(GoogleDriveReference ref, String cursor) {
        if (sessionAttributes != null && StringUtils.isNotBlank(cursor)) {
            sessionAttributes.setString(deltaCursorKey(ref), cursor);
        }
    }

    void clearDeltaCursor(GoogleDriveReference ref) {
        if (sessionAttributes != null) {
            sessionAttributes.setString(deltaCursorKey(ref), "");
        }
    }

    private void validateConfiguredSourceDeltaRoots(CrawlerSession crawler) {
        for (String startRef : crawler.getCrawlContext().getCrawlConfig()
                .getStartReferences()) {
            if (StringUtils.isBlank(startRef)
                    || !StringUtils.startsWith(startRef, "gdrive://")) {
                continue;
            }
            validateConfiguredSourceDeltaRoot(
                    GoogleDriveReference.parse(startRef));
        }
    }

    private void validateConfiguredSourceDeltaRoot(GoogleDriveReference ref) {
        if (ref.isDiscoveryEntry()) {
            return;
        }
        throw new CrawlerException("Unsupported Google Drive SOURCE_DELTA "
                + "start reference: " + ref.toReference()
                + ". Use a user or shared-drive start reference.");
    }

    private static String deltaCursorKey(GoogleDriveReference ref) {
        return DELTA_CURSOR_KEY_PREFIX + ref.toReference();
    }

    static final class GoogleDriveHttpStatusException extends IOException {
        private static final long serialVersionUID = 1L;

        @Getter
        private final int statusCode;

        GoogleDriveHttpStatusException(int statusCode, String message) {
            super(message + " (status=" + statusCode + ")");
            this.statusCode = statusCode;
        }
    }
}
