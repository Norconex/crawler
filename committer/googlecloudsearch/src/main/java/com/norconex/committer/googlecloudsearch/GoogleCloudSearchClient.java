/* Copyright 2009-2026 Norconex Inc.
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
package com.norconex.committer.googlecloudsearch;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.cloudsearch.v1.CloudSearch;
import com.google.api.services.cloudsearch.v1.model.GSuitePrincipal;
import com.google.api.services.cloudsearch.v1.model.IndexItemRequest;
import com.google.api.services.cloudsearch.v1.model.Item;
import com.google.api.services.cloudsearch.v1.model.ItemAcl;
import com.google.api.services.cloudsearch.v1.model.ItemContent;
import com.google.api.services.cloudsearch.v1.model.ItemMetadata;
import com.google.api.services.cloudsearch.v1.model.ItemStructuredData;
import com.google.api.services.cloudsearch.v1.model.Media;
import com.google.api.services.cloudsearch.v1.model.NamedProperty;
import com.google.api.services.cloudsearch.v1.model.Operation;
import com.google.api.services.cloudsearch.v1.model.Principal;
import com.google.api.services.cloudsearch.v1.model.StartUploadItemRequest;
import com.google.api.services.cloudsearch.v1.model.StructuredDataObject;
import com.google.api.services.cloudsearch.v1.model.TextValues;
import com.google.api.services.cloudsearch.v1.model.UploadItemRef;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.CommitterRequest;
import com.norconex.committer.core.CommitterUtil;
import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.AclInheritanceMapping;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.AclMapping;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.AclTarget;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.PrincipalType;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.UploadFormat;
import com.norconex.commons.lang.map.Properties;

import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Google Cloud Search indexing API client.
 * </p>
 */
@Slf4j
class GoogleCloudSearchClient {

    /** Metadata field expected to hold base64-encoded raw content. */
    static final String FIELD_BINARY_CONTENT = "binaryContent";
    /** Metadata field holding the resolved content type, if any. */
    static final String FIELD_CONTENT_TYPE = "document.contentType";

    private static final String DEFAULT_OBJECT_TYPE = "document";
    private static final String DEFAULT_TEXT_CONTENT_TYPE = "text/plain";
    private static final String DEFAULT_BINARY_CONTENT_TYPE =
            "application/octet-stream";
    private static final int INLINE_CONTENT_MAX_BYTES = 102400;
    private static final String INDEXING_SCOPE =
            "https://www.googleapis.com/auth/cloud_search.indexing";
    private static final String CONTENT_ITEM_TYPE = "CONTENT_ITEM";

    private final GoogleCloudSearchCommitterConfig config;
    private final AtomicLong versionSequence = new AtomicLong();
    private final CloudSearch cloudSearch;
    private final LongSupplier clock;

    GoogleCloudSearchClient(GoogleCloudSearchCommitterConfig config)
            throws CommitterException {
        this(config, buildCloudSearch(config), System::currentTimeMillis);
    }

    // Visible for testing: allows injecting a CloudSearch client built
    // against a mock HttpTransport, and a fixed clock.
    GoogleCloudSearchClient(
            GoogleCloudSearchCommitterConfig config, CloudSearch cloudSearch,
            LongSupplier clock) {
        this.config = config;
        this.cloudSearch = cloudSearch;
        this.clock = clock;
    }

    private static CloudSearch buildCloudSearch(
            GoogleCloudSearchCommitterConfig config)
            throws CommitterException {
        try {
            var initializer = createRequestInitializer(
                    config.getSecretKeyPath());
            var builder = new CloudSearch.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(), initializer)
                            .setApplicationName(config.getApplicationName());
            if (StringUtils.isNotBlank(config.getApiEndpoint())) {
                builder.setRootUrl(
                        ensureTrailingSlash(config.getApiEndpoint()));
            }
            return builder.build();
        } catch (IOException | GeneralSecurityException e) {
            throw new CommitterException(
                    "Could not initialize Google Cloud Search client.", e);
        }
    }

    private static HttpRequestInitializer createRequestInitializer(
            String secretKeyPath) throws IOException {
        try (InputStream input = new FileInputStream(secretKeyPath)) {
            GoogleCredentials credentials = ServiceAccountCredentials
                    .fromStream(input)
                    .createScoped(Collections.singleton(INDEXING_SCOPE));
            return new HttpCredentialsAdapter(credentials);
        }
    }

    private static String ensureTrailingSlash(String value) {
        return value.endsWith("/") ? value : value + "/";
    }

    void post(Iterator<CommitterRequest> it) throws CommitterException {
        var failures = new BatchFailureCollector();
        var operationCount = 0;
        try {
            var batch = cloudSearch.batch();
            while (it.hasNext()) {
                var request = it.next();
                if (request instanceof UpsertRequest upsert) {
                    queueUpsert(batch, upsert, failures);
                } else if (request instanceof DeleteRequest delete) {
                    queueDelete(batch, delete, failures);
                } else {
                    throw new CommitterException(
                            "Unsupported request type: " + request);
                }
                operationCount++;
            }
            if (operationCount > 0) {
                batch.execute();
                failures.throwIfAny();
                LOG.info(
                        "Sent {} commit operations to Google Cloud Search.",
                        operationCount);
            }
        } catch (CommitterException e) {
            throw e;
        } catch (Exception e) {
            throw new CommitterException(
                    "Could not commit batch to Google Cloud Search.", e);
        }
    }

    void close() {
        // No persistent connection to release for the Google API client.
    }

    private void queueUpsert(
            BatchRequest batch, UpsertRequest request,
            BatchFailureCollector failures)
            throws IOException, CommitterException {
        var sourceId = CommitterUtil.extractSourceIdValue(
                request, config.getSourceIdField(),
                config.isKeepSourceIdField());
        var itemName = buildItemName(sourceId);
        var contentType = resolveContentType(request.getMetadata());

        var item = new Item()
                .setName(itemName)
                .setItemType(CONTENT_ITEM_TYPE)
                .setVersion(nextVersion())
                .setMetadata(buildMetadata(request, contentType))
                .setStructuredData(buildStructuredData(request.getMetadata()))
                .setAcl(buildAcl(request.getMetadata()));

        var itemContent = buildItemContent(request, itemName, contentType);
        if (itemContent != null) {
            item.setContent(itemContent);
        }

        var indexRequest = new IndexItemRequest()
                .setConnectorName(config.getConnectorName())
                .setItem(item);

        cloudSearch.indexing()
                .datasources()
                .items()
                .index(itemName, indexRequest)
                .queue(batch, failures);
    }

    private void queueDelete(
            BatchRequest batch, DeleteRequest request,
            BatchFailureCollector failures)
            throws IOException, CommitterException {
        var sourceId = CommitterUtil.extractSourceIdValue(
                request, config.getSourceIdField(),
                config.isKeepSourceIdField());
        var itemName = buildItemName(sourceId);
        cloudSearch.indexing()
                .datasources()
                .items()
                .delete(itemName)
                .queue(batch, failures);
    }

    private ItemMetadata buildMetadata(
            UpsertRequest request, String contentType) {
        var metadata = request.getMetadata();
        var itemMetadata = new ItemMetadata();

        var title = metadata.getString(config.getTitleField());
        if (StringUtils.isNotBlank(title)) {
            itemMetadata.setTitle(title);
        }

        var objectType = metadata.getString(config.getObjectTypeField());
        itemMetadata.setObjectType(
                StringUtils.defaultIfBlank(objectType, DEFAULT_OBJECT_TYPE));
        itemMetadata.setMimeType(contentType);

        var containerName =
                metadataValue(metadata, config.getContainerNameField());
        if (StringUtils.isNotBlank(containerName)) {
            itemMetadata.setContainerName(containerName);
        }

        var contentLanguage =
                metadataValue(metadata, config.getContentLanguageField());
        if (StringUtils.isNotBlank(contentLanguage)) {
            itemMetadata.setContentLanguage(contentLanguage);
        }

        var updateTime =
                metadataValue(metadata, config.getUpdateTimeField());
        if (StringUtils.isNotBlank(updateTime)) {
            var parsedTime = toRfc3339(updateTime);
            if (parsedTime != null) {
                itemMetadata.setUpdateTime(parsedTime);
            }
        }

        var sourceRepositoryUrl =
                StringUtils.isNotBlank(config.getSourceRepositoryUrlField())
                        ? metadataValue(
                                metadata, config.getSourceRepositoryUrlField())
                        : request.getReference();
        if (StringUtils.isNotBlank(sourceRepositoryUrl)) {
            itemMetadata.setSourceRepositoryUrl(sourceRepositoryUrl);
        }
        return itemMetadata;
    }

    private ItemStructuredData buildStructuredData(Properties metadata) {
        List<NamedProperty> properties = new ArrayList<>();
        var excludedFields = buildStructuredDataExclusions();

        for (Entry<String, List<String>> entry : metadata.entrySet()) {
            if (excludedFields.contains(entry.getKey())
                    || entry.getValue() == null
                    || entry.getValue().isEmpty()) {
                continue;
            }
            properties.add(
                    new NamedProperty()
                            .setName(entry.getKey())
                            .setTextValues(
                                    new TextValues().setValues(
                                            new ArrayList<>(
                                                    entry.getValue()))));
        }
        if (properties.isEmpty()) {
            return null;
        }
        return new ItemStructuredData().setObject(
                new StructuredDataObject().setProperties(properties));
    }

    private Set<String> buildStructuredDataExclusions() {
        Set<String> excludedFields = new HashSet<>();
        excludedFields.add(FIELD_BINARY_CONTENT);
        if (StringUtils.isNotBlank(config.getSourceIdField())) {
            excludedFields.add(config.getSourceIdField());
        }
        for (AclMapping mapping : config.getAclMappings()) {
            excludedFields.add(mapping.getFromField());
        }
        if (StringUtils.isNotBlank(
                config.getAclInheritance().getFromField())) {
            excludedFields.add(config.getAclInheritance().getFromField());
        }
        return excludedFields;
    }

    ItemAcl buildAcl(Properties metadata) throws CommitterException {
        List<Principal> readers = new ArrayList<>();
        List<Principal> deniedReaders = new ArrayList<>();
        List<Principal> owners = new ArrayList<>();

        for (AclMapping mapping : config.getAclMappings()) {
            var values = metadata.getStrings(mapping.getFromField());
            if (values == null || values.isEmpty()) {
                continue;
            }
            for (String value : values) {
                var principal =
                        toPrincipal(mapping.getPrincipalType(), value);
                if (principal == null) {
                    continue;
                }
                switch (mapping.getTarget()) {
                    case READERS -> readers.add(principal);
                    case DENIED_READERS -> deniedReaders.add(principal);
                    case OWNERS -> owners.add(principal);
                    default -> throw new CommitterException(
                            "Unsupported ACL target: " + mapping.getTarget());
                }
            }
        }

        AclInheritanceMapping aclInheritance = config.getAclInheritance();
        var parentValue =
                metadataValue(metadata, aclInheritance.getFromField());
        var hasInheritance = StringUtils.isNotBlank(parentValue);
        if (readers.isEmpty() && deniedReaders.isEmpty() && owners.isEmpty()
                && !hasInheritance) {
            return null;
        }

        var acl = new ItemAcl();
        if (!readers.isEmpty()) {
            acl.setReaders(readers);
        }
        if (!deniedReaders.isEmpty()) {
            acl.setDeniedReaders(deniedReaders);
        }
        if (!owners.isEmpty()) {
            acl.setOwners(owners);
        }
        if (hasInheritance) {
            acl.setInheritAclFrom(buildItemName(parentValue));
            acl.setAclInheritanceType(
                    aclInheritance.getAclInheritanceType().name());
        }
        return acl;
    }

    Principal toPrincipal(PrincipalType principalType, String value) {
        if (principalType == PrincipalType.CUSTOMER) {
            return new Principal().setGsuitePrincipal(
                    new GSuitePrincipal().setGsuiteDomain(true));
        }
        if (StringUtils.isBlank(value)) {
            return null;
        }
        var gsuitePrincipal = new GSuitePrincipal();
        if (principalType == PrincipalType.USER) {
            gsuitePrincipal.setGsuiteUserEmail(value);
        } else if (principalType == PrincipalType.GROUP) {
            gsuitePrincipal.setGsuiteGroupEmail(value);
        }
        return new Principal().setGsuitePrincipal(gsuitePrincipal);
    }

    private ItemContent buildItemContent(
            UpsertRequest request, String itemName, String contentType)
            throws IOException, CommitterException {
        if (config.getUploadFormat() == UploadFormat.RAW) {
            return uploadContent(
                    itemName, contentType, loadRawContent(request), "RAW");
        }

        var textBytes = IOUtils.toString(request.getContent(), UTF_8)
                .getBytes(UTF_8);
        if (textBytes.length <= INLINE_CONTENT_MAX_BYTES) {
            return new ItemContent()
                    .setContentFormat("TEXT")
                    .encodeInlineContent(textBytes);
        }
        return uploadContent(itemName, contentType, textBytes, "TEXT");
    }

    private ItemContent uploadContent(
            String itemName, String contentType, byte[] content,
            String contentFormat) throws IOException {
        var uploadItemRef = cloudSearch.indexing()
                .datasources()
                .items()
                .upload(
                        itemName,
                        new StartUploadItemRequest()
                                .setConnectorName(config.getConnectorName()))
                .execute();

        var uploadRequest = cloudSearch.media().upload(
                uploadItemRef.getName(),
                new Media().setResourceName(uploadItemRef.getName()),
                new ByteArrayContent(contentType, content));
        uploadRequest.getMediaHttpUploader().setDirectUploadEnabled(true);
        uploadRequest.execute();

        return new ItemContent()
                .setContentFormat(contentFormat)
                .setContentDataRef(uploadItemRef);
    }

    private byte[] loadRawContent(UpsertRequest request) throws IOException {
        var encoded = request.getMetadata().getString(FIELD_BINARY_CONTENT);
        if (encoded != null) {
            return Base64.getDecoder().decode(encoded);
        }
        LOG.warn(
                "Raw upload selected but '{}' is missing. Falling back to "
                        + "request content for {}.",
                FIELD_BINARY_CONTENT, request.getReference());
        return IOUtils.toByteArray(request.getContent());
    }

    String resolveContentType(Properties metadata) {
        var contentType = metadata.getString(FIELD_CONTENT_TYPE);
        if (StringUtils.isNotBlank(contentType)) {
            return contentType;
        }
        return config.getUploadFormat() == UploadFormat.RAW
                ? DEFAULT_BINARY_CONTENT_TYPE
                : DEFAULT_TEXT_CONTENT_TYPE;
    }

    String buildItemName(String sourceId) throws CommitterException {
        if (StringUtils.isBlank(sourceId)) {
            throw new CommitterException("Document id cannot be empty.");
        }
        return "datasources/" + config.getDataSourceId()
                + "/items/" + encodeItemId(sourceId);
    }

    String encodeItemId(String sourceId) {
        var encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sourceId.getBytes(UTF_8));
        if (encoded.length() <= 1500) {
            return encoded;
        }
        return "sha256-" + sha256Hex(sourceId);
    }

    String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(value.getBytes(UTF_8));
            var builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }

    String nextVersion() {
        var millis = clock.getAsLong();
        var sequence = versionSequence.incrementAndGet();
        return String.format("%019d-%06d", millis, sequence);
    }

    String toRfc3339(String value) {
        try {
            return Instant.parse(value).toString();
        } catch (DateTimeParseException e) {
            // Try other supported formats.
        }
        try {
            return OffsetDateTime.parse(value).toInstant().toString();
        } catch (DateTimeParseException e) {
            // Try other supported formats.
        }
        try {
            return ZonedDateTime
                    .parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toString();
        } catch (DateTimeParseException e) {
            // Try other supported formats.
        }
        try {
            return LocalDateTime.parse(value)
                    .toInstant(ZoneOffset.UTC).toString();
        } catch (DateTimeParseException e) {
            LOG.debug("Ignoring unparsable updateTime value '{}'.", value);
        }
        return null;
    }

    private String metadataValue(Properties metadata, String field) {
        if (StringUtils.isBlank(field)) {
            return null;
        }
        return metadata.getString(field);
    }

    private static final class BatchFailureCollector
            extends JsonBatchCallback<Operation> {
        private final List<String> failures = new ArrayList<>();

        @Override
        public void onSuccess(Operation operation,
                HttpHeaders responseHeaders) {
            // NOOP
        }

        @Override
        public void onFailure(GoogleJsonError e, HttpHeaders responseHeaders) {
            try {
                failures.add(e.toPrettyString());
            } catch (IOException ioe) {
                failures.add(String.valueOf(e));
            }
        }

        void throwIfAny() throws CommitterException {
            if (!failures.isEmpty()) {
                throw new CommitterException(
                        "Google Cloud Search returned batch failures: "
                                + StringUtils.join(failures, "\n"));
            }
        }
    }
}
