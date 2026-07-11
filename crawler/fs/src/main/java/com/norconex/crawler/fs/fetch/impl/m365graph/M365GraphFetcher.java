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
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.norconex.commons.lang.Sleeper;
import com.norconex.commons.lang.file.ContentType;
import com.norconex.crawler.core.CrawlerConfig.ChangeDiscovery;
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

/**
 * Microsoft 365 Graph fetcher for SharePoint Online and OneDrive.
 *
 * <p>
 * Supported reference schemes:
 * </p>
 * <ul>
 *   <li><code>m365sp://{tenant}/sites/{siteId}/drives/{driveId}/items/{itemId}</code></li>
 *   <li><code>m365od://{tenant}/users/{userId}/drives/{driveId}/items/{itemId}</code></li>
 * </ul>
 */
@ToString
@EqualsAndHashCode
public class M365GraphFetcher extends AbstractFetcher<M365GraphFetcherConfig> {

    private static final String META_PREFIX = PREFIX + "m365.";
    private static final String DELTA_CURSOR_KEY_PREFIX =
            "m365graph.delta.cursor.";
    private static final int DELTA_MAX_PAGES = 1000;
    private static final Set<Integer> DELTA_CURSOR_RESET_STATUSES = Set.of(
            400,
            404,
            410);

    @Getter
    private final M365GraphFetcherConfig configuration =
            new M365GraphFetcherConfig();

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

    @Override
    protected void fetcherStartup(CrawlerSession crawler) {
        sessionAttributes = crawler.getSessionAttributes();
        sourceDeltaEnabled = crawler.isIncremental()
                && crawler.getCrawlContext().getCrawlConfig()
                        .getChangeDiscovery() == ChangeDiscovery.SOURCE_DELTA;
    }

    @Override
    protected void fetcherShutdown(CrawlerSession crawler) {
        sourceDeltaEnabled = false;
        sessionAttributes = null;
    }

    @Override
    protected boolean acceptRequest(@NonNull FetchRequest fetchRequest) {
        return referenceStartsWith(fetchRequest, "m365sp://", "m365od://");
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
        var ref = M365GraphReference.parse(doc.getReference());
        validateTenant(ref);
        if (ref.kind() != M365GraphReference.Kind.ITEM) {
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

            var isFile = item.has("file");
            var isFolder = item.has("folder");

            if (isFile && FetchDirective.DOCUMENT.is(req.getFetchDirective())) {
                fetchContent(doc, ref);
            }
            fetchMetadata(doc, item);

            return GenericFileFetchResponse.builder()
                    .processingOutcome(ProcessingOutcome.NEW)
                    .file(isFile)
                    .folder(isFolder)
                    .build();
        } catch (GraphHttpStatusException e) {
            return GenericFileFetchResponse.builder()
                    .processingOutcome(ProcessingOutcome.BAD_STATUS)
                    .build();
        } catch (IOException e) {
            throw new FetchException(
                    "Could not fetch Microsoft 365 reference: "
                            + doc.getReference(),
                    e);
        }
    }

    private FetchResponse fetchChildPaths(FolderPathsFetchRequest req)
            throws FetchException {
        var rawRef = M365GraphReference.parse(req.getDoc().getReference());
        validateTenant(rawRef);
        try {
            var ref = resolveDiscoveryReference(rawRef);
            if (ref == null) {
                return notFoundFolderPathsResponse();
            }

            if (ref.isDiscoveryEntry()) {
                return fetchDiscoveryEntryChildPaths(ref);
            }

            if (isSourceDeltaEnabled()
                    && ref.kind() == M365GraphReference.Kind.DRIVE) {
                return fetchDriveDeltaChildPaths(ref);
            }

            var children = fetchChildrenNode(ref);
            if (children == null) {
                return notFoundFolderPathsResponse();
            }

            Set<FsPath> childPaths = new HashSet<>();
            for (JsonNode item : children.path("value")) {
                var childId = item.path("id").asText(null);
                if (StringUtils.isBlank(childId)) {
                    continue;
                }
                var childRef = ref.child(childId).toReference();
                var fsPath = new FsPath(childRef);
                fsPath.setFile(item.has("file"));
                fsPath.setFolder(item.has("folder"));
                childPaths.add(fsPath);
            }

            return GenericFolderPathsFetchResponse.builder()
                    .processingOutcome(ProcessingOutcome.NEW)
                    .childPaths(childPaths)
                    .build();
        } catch (GraphHttpStatusException e) {
            return GenericFolderPathsFetchResponse.builder()
                    .processingOutcome(ProcessingOutcome.BAD_STATUS)
                    .childPaths(Set.of())
                    .build();
        } catch (IOException e) {
            throw new FetchException(
                    "Could not fetch Microsoft 365 child references: "
                            + req.getDoc().getReference(),
                    e);
        }
    }

    private M365GraphReference resolveDiscoveryReference(
            M365GraphReference ref) throws IOException {
        if (ref.kind() != M365GraphReference.Kind.SITE_URL) {
            return ref;
        }
        var site = resolveSiteNode(ref);
        if (site == null) {
            return null;
        }
        var siteId = site.path("id").asText(null);
        if (StringUtils.isBlank(siteId)) {
            throw new IOException("Resolved site payload did not contain id.");
        }
        return ref.site(siteId);
    }

    private FetchResponse fetchDiscoveryEntryChildPaths(M365GraphReference ref)
            throws IOException {
        var drives = fetchDrivesNode(ref);
        if (drives == null) {
            return notFoundFolderPathsResponse();
        }

        Set<FsPath> childPaths = new HashSet<>();
        for (JsonNode drive : drives.path("value")) {
            var driveId = drive.path("id").asText(null);
            if (StringUtils.isBlank(driveId)) {
                continue;
            }
            var driveRef = ref.drive(driveId).toReference();
            var fsPath = new FsPath(driveRef);
            fsPath.setFile(false);
            fsPath.setFolder(true);
            childPaths.add(fsPath);
        }

        return GenericFolderPathsFetchResponse.builder()
                .processingOutcome(ProcessingOutcome.NEW)
                .childPaths(childPaths)
                .build();
    }

    private static FetchResponse notFoundFolderPathsResponse() {
        return GenericFolderPathsFetchResponse.builder()
                .processingOutcome(ProcessingOutcome.NOT_FOUND)
                .childPaths(Set.of())
                .build();
    }

    private FetchResponse fetchDriveDeltaChildPaths(M365GraphReference ref)
            throws IOException {
        var usingStoredCursor = false;
        var pagePathOrUrl =
                getDeltaCursor(ref).orElseGet(() -> ref.driveDeltaApiPath());
        if (!StringUtils.equals(pagePathOrUrl, ref.driveDeltaApiPath())) {
            usingStoredCursor = true;
        }

        while (true) {
            try {
                return fetchDriveDeltaPages(ref, pagePathOrUrl);
            } catch (GraphHttpStatusException e) {
                if (usingStoredCursor
                        && DELTA_CURSOR_RESET_STATUSES
                                .contains(e.getStatusCode())) {
                    clearDeltaCursor(ref);
                    pagePathOrUrl = ref.driveDeltaApiPath();
                    usingStoredCursor = false;
                    continue;
                }
                throw e;
            }
        }
    }

    private FetchResponse fetchDriveDeltaPages(
            M365GraphReference ref,
            String firstPagePathOrUrl) throws IOException {
        Set<FsPath> childPaths = new HashSet<>();
        String pagePathOrUrl = firstPagePathOrUrl;
        String deltaLink = null;
        int pageCount = 0;

        while (StringUtils.isNotBlank(pagePathOrUrl)) {
            pageCount++;
            if (pageCount > DELTA_MAX_PAGES) {
                throw new IOException(
                        "Exceeded maximum number of Microsoft Graph "
                                + "delta pages (" + DELTA_MAX_PAGES + ").");
            }

            var page = fetchDeltaNode(pagePathOrUrl);
            if (page == null) {
                return notFoundFolderPathsResponse();
            }
            for (JsonNode item : page.path("value")) {
                var childId = item.path("id").asText(null);
                if (StringUtils.isBlank(childId) || item.has("root")) {
                    continue;
                }

                var fsPath = new FsPath(ref.child(childId).toReference());
                if (item.has("deleted")) {
                    fsPath.setFile(true);
                    fsPath.setFolder(false);
                } else {
                    fsPath.setFile(item.has("file"));
                    fsPath.setFolder(item.has("folder"));
                }
                childPaths.add(fsPath);
            }

            var nextLink = StringUtils.trimToNull(
                    page.path("@odata.nextLink").asText(null));
            var currentDelta = StringUtils.trimToNull(
                    page.path("@odata.deltaLink").asText(null));
            if (StringUtils.isNotBlank(currentDelta)) {
                deltaLink = currentDelta;
            }
            pagePathOrUrl = nextLink;
        }

        if (StringUtils.isNotBlank(deltaLink)) {
            setDeltaCursor(ref, deltaLink);
        }

        return GenericFolderPathsFetchResponse.builder()
                .processingOutcome(ProcessingOutcome.NEW)
                .childPaths(childPaths)
                .build();
    }

    void fetchMetadata(Doc doc, JsonNode item) {
        var meta = doc.getMetadata();

        if (item.hasNonNull("size")) {
            meta.set(FsDocMetadata.FILE_SIZE, item.path("size").asLong());
        }

        var modified = item.path("lastModifiedDateTime").asText(null);
        if (StringUtils.isNotBlank(modified)) {
            meta.set(FsDocMetadata.LAST_MODIFIED,
                    Instant.parse(modified).toEpochMilli());
        }

        var created = item.path("createdDateTime").asText(null);
        if (StringUtils.isNotBlank(created)) {
            meta.set(META_PREFIX + "created", created);
        }

        var webUrl = item.path("webUrl").asText(null);
        if (StringUtils.isNotBlank(webUrl)) {
            meta.set(META_PREFIX + "webUrl", webUrl);
        }

        var mimeType = item.path("file").path("mimeType").asText(null);
        if (StringUtils.isNotBlank(mimeType)) {
            meta.set(DocMetaConstants.CONTENT_TYPE, mimeType);
            doc.setContentType(ContentType.valueOf(mimeType));
        }

        var owner = item.path("lastModifiedBy").path("user");
        var ownerDisplay = owner.path("displayName").asText(null);
        if (StringUtils.isNotBlank(ownerDisplay)) {
            meta.set(META_PREFIX + "owner.displayName", ownerDisplay);
        }
        var ownerId = owner.path("id").asText(null);
        if (StringUtils.isNotBlank(ownerId)) {
            meta.set(META_PREFIX + "owner.id", ownerId);
        }

        var parentPath = item.path("parentReference").path("path")
                .asText(null);
        if (StringUtils.isNotBlank(parentPath)) {
            meta.set(META_PREFIX + "parent.path", parentPath);
        }
    }

    void fetchContent(Doc doc, M365GraphReference ref) throws IOException {
        var bytes = fetchContentBytes(ref);
        try (var is = doc.getStreamFactory().newInputStream(
                new ByteArrayInputStream(bytes))) {
            is.enforceFullCaching();
            doc.setInputStream(is);
        }
    }

    JsonNode fetchItemNode(M365GraphReference ref) throws IOException {
        var response = executeGet(ref.itemApiPath());
        if (isNotFoundStatus(response.statusCode())) {
            return null;
        }
        ensureStatus(response, "read item");
        return objectMapper.readTree(response.body());
    }

    JsonNode fetchChildrenNode(M365GraphReference ref) throws IOException {
        var path = ref.kind() == M365GraphReference.Kind.DRIVE
                ? ref.driveRootApiPath() + "/children"
                : ref.itemApiPath() + "/children";
        var response = executeGet(path);
        if (isNotFoundStatus(response.statusCode())) {
            return null;
        }
        ensureStatus(response, "read children");
        return objectMapper.readTree(response.body());
    }

    JsonNode fetchDrivesNode(M365GraphReference ref) throws IOException {
        var response = executeGet(ref.drivesApiPath());
        if (isNotFoundStatus(response.statusCode())) {
            return null;
        }
        ensureStatus(response, "read drives");
        return objectMapper.readTree(response.body());
    }

    JsonNode resolveSiteNode(M365GraphReference ref) throws IOException {
        var response = executeGet(ref.resolveSiteApiPath());
        if (isNotFoundStatus(response.statusCode())) {
            return null;
        }
        ensureStatus(response, "resolve site");
        return objectMapper.readTree(response.body());
    }

    JsonNode fetchDeltaNode(String pathOrUrl) throws IOException {
        var response = executeGet(pathOrUrl);
        if (isNotFoundStatus(response.statusCode())) {
            return null;
        }
        ensureStatus(response, "read drive delta");
        return objectMapper.readTree(response.body());
    }

    byte[] fetchContentBytes(M365GraphReference ref) throws IOException {
        var response = executeGet(ref.itemApiPath() + "/content");
        ensureStatus(response, "download content");
        return response.body();
    }

    private HttpResponse<byte[]> executeGet(String pathOrUrl)
            throws IOException {
        var url = pathOrUrl;
        if (!StringUtils.startsWithAny(StringUtils.defaultString(pathOrUrl),
                "http://", "https://")) {
            url = normalizeBaseUrl(configuration.getGraphBaseUrl()) + pathOrUrl;
        }
        var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + getAccessToken())
                .header("Accept", "application/json")
                .GET()
                .build();
        var cfg = configuration;
        var maxAttempts = cfg.isNativeRetryEnabled()
                ? Math.max(0, cfg.getNativeRetryMaxRetries()) + 1
                : 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            var response = sendRequest(request);
            if (cfg.isNativeRetryEnabled()
                    && isNativeRetryStatus(response.statusCode())
                    && attempt < maxAttempts) {
                sleepBeforeRetry(response, attempt);
                continue;
            }
            return response;
        }
        throw new IOException("Could not call Microsoft Graph URL: " + url);
    }

    HttpResponse<byte[]> sendRequest(HttpRequest request) throws IOException {
        try {
            return httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling Microsoft Graph.",
                    e);
        }
    }

    private synchronized String getAccessToken() throws IOException {
        if (StringUtils.isNotBlank(accessToken)
                && Instant.now().isBefore(accessTokenExpiry.minusSeconds(30))) {
            return accessToken;
        }

        var cfg = configuration;
        if (StringUtils.isAnyBlank(
                cfg.getTenantId(), cfg.getClientId(), cfg.getClientSecret())) {
            throw new IOException(
                    "Microsoft Graph app-only auth requires tenantId, "
                            + "clientId, and clientSecret.");
        }

        var tokenUrl = normalizeBaseUrl(cfg.getAuthorityHost())
                + "/" + urlEncode(cfg.getTenantId())
                + "/oauth2/v2.0/token";
        var form = "grant_type=client_credentials"
                + "&client_id=" + urlEncode(cfg.getClientId())
                + "&client_secret=" + urlEncode(cfg.getClientSecret())
                + "&scope=" + urlEncode("https://graph.microsoft.com/.default");

        var request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        var response = sendRequest(request);

        ensure2xx(response, "acquire access token");
        var tokenJson = objectMapper.readTree(response.body());
        accessToken = tokenJson.path("access_token").asText(null);
        if (StringUtils.isBlank(accessToken)) {
            throw new IOException(
                    "Token response did not contain access_token.");
        }
        var expiresIn = tokenJson.path("expires_in").asLong(3600L);
        accessTokenExpiry = Instant.now().plusSeconds(expiresIn);
        return accessToken;
    }

    private void validateTenant(M365GraphReference ref) {
        var configured = configuration.getTenantId();
        if (StringUtils.isNotBlank(configured)
                && !StringUtils.equalsIgnoreCase(configured, ref.tenantId())) {
            throw new IllegalArgumentException(
                    "Reference tenant does not match configured tenantId: "
                            + ref.tenantId());
        }
    }

    boolean isValidStatus(int statusCode) {
        return configuration.getValidStatusCodes().contains(statusCode);
    }

    boolean isNotFoundStatus(int statusCode) {
        return configuration.getNotFoundStatusCodes().contains(statusCode);
    }

    boolean isNativeRetryStatus(int statusCode) {
        return configuration.getNativeRetryStatusCodes().contains(statusCode);
    }

    private void ensureStatus(HttpResponse<byte[]> response,
            String what)
            throws IOException {
        var status = response.statusCode();
        if (isValidStatus(status)) {
            return;
        }
        throw new GraphHttpStatusException(status,
                "Could not " + what + " from Microsoft Graph. "
                        + "HTTP status=" + status);
    }

    private static void ensure2xx(HttpResponse<byte[]> response,
            String what)
            throws IOException {
        var status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        throw new IOException("Could not " + what + " from Microsoft Graph. "
                + "HTTP status=" + status);
    }

    private void sleepBeforeRetry(HttpResponse<byte[]> response, int attempt) {
        var delay = parseRetryAfter(response);
        if (delay == null || delay.isNegative()) {
            var base = configuration.getNativeRetryBaseDelay();
            if (base == null || base.isNegative()) {
                base = M365GraphFetcherConfig.DEFAULT_NATIVE_RETRY_BASE_DELAY;
            }
            var multiplier = Math.min(1L << Math.max(0, attempt - 1), 64L);
            delay = base.multipliedBy(multiplier);
        }
        Sleeper.sleepMillis(Math.max(0L, delay.toMillis()));
    }

    private static Duration parseRetryAfter(HttpResponse<byte[]> response) {
        var retryAfter = response.headers().firstValue("Retry-After")
                .orElse(null);
        if (StringUtils.isBlank(retryAfter)) {
            return null;
        }
        var value = retryAfter.trim();
        if (StringUtils.isNumeric(value)) {
            return Duration.ofSeconds(Long.parseLong(value));
        }
        try {
            var when = ZonedDateTime.parse(value,
                    DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            var duration = Duration.between(Instant.now(), when);
            return duration.isNegative() ? Duration.ZERO : duration;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String normalizeBaseUrl(String url) {
        return StringUtils.removeEnd(StringUtils.defaultString(url), "/");
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(
                StringUtils.defaultString(value),
                StandardCharsets.UTF_8);
    }

    boolean isSourceDeltaEnabled() {
        return sourceDeltaEnabled;
    }

    Optional<String> getDeltaCursor(M365GraphReference ref) {
        if (sessionAttributes == null) {
            return Optional.empty();
        }
        return sessionAttributes.getString(deltaCursorKey(ref));
    }

    void setDeltaCursor(M365GraphReference ref, String cursor) {
        if (sessionAttributes != null && StringUtils.isNotBlank(cursor)) {
            sessionAttributes.setString(deltaCursorKey(ref), cursor);
        }
    }

    void clearDeltaCursor(M365GraphReference ref) {
        if (sessionAttributes != null) {
            sessionAttributes.setString(deltaCursorKey(ref), "");
        }
    }

    private static String deltaCursorKey(M365GraphReference ref) {
        return DELTA_CURSOR_KEY_PREFIX + ref.toReference();
    }

    static final class GraphHttpStatusException extends IOException {
        private static final long serialVersionUID = 1L;
        @Getter
        private final int statusCode;

        GraphHttpStatusException(int statusCode, String message) {
            super(message + " (status=" + statusCode + ")");
            this.statusCode = statusCode;
        }
    }
}
