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
import java.time.LocalDate;
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
import com.google.api.client.http.HttpBackOffIOExceptionHandler;
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.services.cloudsearch.v1.CloudSearch;
import com.google.api.services.cloudsearch.v1.model.Date;
import com.google.api.services.cloudsearch.v1.model.DateValues;
import com.google.api.services.cloudsearch.v1.model.DoubleValues;
import com.google.api.services.cloudsearch.v1.model.EnumValues;
import com.google.api.services.cloudsearch.v1.model.GSuitePrincipal;
import com.google.api.services.cloudsearch.v1.model.HtmlValues;
import com.google.api.services.cloudsearch.v1.model.IndexItemRequest;
import com.google.api.services.cloudsearch.v1.model.IntegerValues;
import com.google.api.services.cloudsearch.v1.model.Item;
import com.google.api.services.cloudsearch.v1.model.ItemAcl;
import com.google.api.services.cloudsearch.v1.model.ItemContent;
import com.google.api.services.cloudsearch.v1.model.ItemMetadata;
import com.google.api.services.cloudsearch.v1.model.ItemStructuredData;
import com.google.api.services.cloudsearch.v1.model.Media;
import com.google.api.services.cloudsearch.v1.model.NamedProperty;
import com.google.api.services.cloudsearch.v1.model.Operation;
import com.google.api.services.cloudsearch.v1.model.Principal;
import com.google.api.services.cloudsearch.v1.model.SearchQualityMetadata;
import com.google.api.services.cloudsearch.v1.model.StartUploadItemRequest;
import com.google.api.services.cloudsearch.v1.model.StructuredDataObject;
import com.google.api.services.cloudsearch.v1.model.TextValues;
import com.google.api.services.cloudsearch.v1.model.TimestampValues;
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
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.MetadataMapping;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.PrincipalType;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.StructuredDataType;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.UploadFormat;
import com.norconex.commons.lang.ExceptionUtil;
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

    private static final String DEFAULT_TEXT_CONTENT_TYPE = "text/plain";
    private static final String DEFAULT_BINARY_CONTENT_TYPE =
            "application/octet-stream";
    private static final int INLINE_CONTENT_MAX_BYTES = 102400;
    private static final String INDEXING_SCOPE =
            "https://www.googleapis.com/auth/cloud_search.indexing";
    private static final String CONTENT_ITEM_TYPE = "CONTENT_ITEM";
    // Google Cloud Search hard limit on the number of values a single
    // enum-typed structured data property may carry per item.
    private static final int MAX_ENUM_VALUES = 32;

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
                    config.getSecretKeyPath(),
                    new HttpRequestOptions()
                            .setConnectTimeoutMillis(
                                    config.getHttpConnectTimeoutMillis())
                            .setReadTimeoutMillis(
                                    config.getHttpReadTimeoutMillis())
                            .setMaxRetries(config.getHttpMaxRetries())
                            .setBackoffInitialIntervalMillis(
                                    config.getHttpBackoffInitialIntervalMillis())
                            .setBackoffMaxIntervalMillis(
                                    config.getHttpBackoffMaxIntervalMillis())
                            .setBackoffMaxElapsedTimeMillis(
                                    config.getHttpBackoffMaxElapsedTimeMillis()));
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
            String secretKeyPath,
            HttpRequestOptions httpOptions) throws IOException {
        try (InputStream input = new FileInputStream(secretKeyPath)) {
            GoogleCredentials credentials = ServiceAccountCredentials
                    .fromStream(input)
                    .createScoped(Collections.singleton(INDEXING_SCOPE));
            var authInitializer = new HttpCredentialsAdapter(credentials);
            var options = httpOptions != null
                    ? httpOptions
                    : new HttpRequestOptions();
            return request -> {
                authInitializer.initialize(request);
                options.apply(request);
            };
        }
    }

    private static String ensureTrailingSlash(String value) {
        return value.endsWith("/") ? value : value + "/";
    }

    static final class HttpRequestOptions {
        private int connectTimeoutMillis = -1;
        private int readTimeoutMillis = -1;
        private int maxRetries = -1;
        private int backoffInitialIntervalMillis = -1;
        private int backoffMaxIntervalMillis = -1;
        private int backoffMaxElapsedTimeMillis = -1;

        HttpRequestOptions setConnectTimeoutMillis(int connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
            return this;
        }

        HttpRequestOptions setReadTimeoutMillis(int readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
            return this;
        }

        HttpRequestOptions setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        HttpRequestOptions setBackoffInitialIntervalMillis(
                int backoffInitialIntervalMillis) {
            this.backoffInitialIntervalMillis = backoffInitialIntervalMillis;
            return this;
        }

        HttpRequestOptions setBackoffMaxIntervalMillis(
                int backoffMaxIntervalMillis) {
            this.backoffMaxIntervalMillis = backoffMaxIntervalMillis;
            return this;
        }

        HttpRequestOptions setBackoffMaxElapsedTimeMillis(
                int backoffMaxElapsedTimeMillis) {
            this.backoffMaxElapsedTimeMillis = backoffMaxElapsedTimeMillis;
            return this;
        }

        void apply(HttpRequest request) {
            if (connectTimeoutMillis >= 0) {
                request.setConnectTimeout(connectTimeoutMillis);
            }
            if (readTimeoutMillis >= 0) {
                request.setReadTimeout(readTimeoutMillis);
            }
            if (maxRetries >= 0) {
                request.setNumberOfRetries(maxRetries);
            }

            if (backoffInitialIntervalMillis < 0
                    && backoffMaxIntervalMillis < 0
                    && backoffMaxElapsedTimeMillis < 0) {
                return;
            }

            var builder = new ExponentialBackOff.Builder();
            if (backoffInitialIntervalMillis >= 0) {
                builder.setInitialIntervalMillis(backoffInitialIntervalMillis);
            }
            if (backoffMaxIntervalMillis >= 0) {
                builder.setMaxIntervalMillis(backoffMaxIntervalMillis);
            }
            if (backoffMaxElapsedTimeMillis >= 0) {
                builder.setMaxElapsedTimeMillis(
                        backoffMaxElapsedTimeMillis);
            }

            request.setIOExceptionHandler(new HttpBackOffIOExceptionHandler(
                    builder.build()));
            request.setUnsuccessfulResponseHandler(
                    new HttpBackOffUnsuccessfulResponseHandler(
                            builder.build())
                                    .setBackOffRequired(
                                            HttpBackOffUnsuccessfulResponseHandler.BackOffRequired.ON_SERVER_ERROR));
        }
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
            // Spell out the cause chain: the CommitterException is reported
            // through a CommitterEvent whose toString only carries this
            // message, so a setup that suppresses stack traces would
            // otherwise leave nothing to diagnose.
            throw new CommitterException(
                    "Could not commit batch to Google Cloud Search:\n"
                            + ExceptionUtil.getFormattedMessages(e),
                    e);
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
                .setVersion(encodeVersion(nextVersion()))
                .setMetadata(buildMetadata(request, contentType))
                .setStructuredData(buildStructuredData(request.getMetadata()))
                .setAcl(buildAcl(request.getMetadata()));

        var itemContent = buildItemContent(request, itemName, contentType);
        if (itemContent != null) {
            item.setContent(itemContent);
        }

        var indexRequest = new IndexItemRequest()
                .setConnectorName(config.getConnectorName())
                .setItem(item)
                .setMode(config.getRequestMode().name());

        cloudSearch.indexing()
                .datasources()
                .items()
                .index(itemName, indexRequest)
                .queue(batch, failures.callbackFor(
                        "index", request.getReference(), false));
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
                // Like Item#version, the delete "version" is a bytes field and
                // must be base64-encoded on the wire. Cloud Search compares the
                // decoded bytes of both lexically, so the encoding here has to
                // match the one used by queueUpsert.
                .setVersion(encodeVersion(nextVersion()))
                .setMode(config.getRequestMode().name())
                .queue(batch, failures.callbackFor(
                        "delete", request.getReference(),
                        !config.isFailOnDeleteNotFound()));
    }

    private ItemMetadata buildMetadata(
            UpsertRequest request, String contentType) {
        var metadata = request.getMetadata();
        var itemMetadata = new ItemMetadata();

        var title = resolveMetadataValue(
                metadata, MetadataField.TITLE, request.getReference(),
                contentType);
        if (StringUtils.isNotBlank(title)) {
            itemMetadata.setTitle(title);
        }

        var objectType = resolveMetadataValue(
                metadata, MetadataField.OBJECT_TYPE,
                request.getReference(), contentType);
        if (StringUtils.isNotBlank(objectType)) {
            itemMetadata.setObjectType(objectType);
        }

        var mimeType = resolveMetadataValue(
                metadata, MetadataField.MIME_TYPE, request.getReference(),
                contentType);
        if (StringUtils.isNotBlank(mimeType)) {
            itemMetadata.setMimeType(mimeType);
        }

        var containerName = resolveMetadataValue(
                metadata, MetadataField.CONTAINER_NAME,
                request.getReference(), contentType);
        if (StringUtils.isNotBlank(containerName)) {
            itemMetadata.setContainerName(containerName);
        }

        var contentLanguage = resolveMetadataValue(
                metadata, MetadataField.CONTENT_LANGUAGE,
                request.getReference(), contentType);
        if (StringUtils.isNotBlank(contentLanguage)) {
            itemMetadata.setContentLanguage(contentLanguage);
        }

        var updateTime = resolveMetadataValue(
                metadata, MetadataField.UPDATE_TIME,
                request.getReference(), contentType);
        if (StringUtils.isNotBlank(updateTime)) {
            var parsedTime = toRfc3339(updateTime);
            if (parsedTime != null) {
                itemMetadata.setUpdateTime(parsedTime);
            }
        }

        var createTime = resolveMetadataValue(
                metadata, MetadataField.CREATE_TIME,
                request.getReference(), contentType);
        if (StringUtils.isNotBlank(createTime)) {
            var parsedTime = toRfc3339(createTime);
            if (parsedTime != null) {
                itemMetadata.setCreateTime(parsedTime);
            }
        }

        var sourceRepositoryUrl = resolveMetadataValue(
                metadata, MetadataField.SOURCE_REPOSITORY_URL,
                request.getReference(), contentType);
        if (StringUtils.isNotBlank(sourceRepositoryUrl)) {
            itemMetadata.setSourceRepositoryUrl(sourceRepositoryUrl);
        }

        var hash = resolveMetadataValue(
                metadata, MetadataField.HASH, request.getReference(),
                contentType);
        if (StringUtils.isNotBlank(hash)) {
            itemMetadata.setHash(hash);
        }

        var keywords = resolveMetadataValues(metadata, MetadataField.KEYWORDS);
        if (keywords != null && !keywords.isEmpty()) {
            itemMetadata.setKeywords(keywords);
        }

        var quality = resolveMetadataValue(
                metadata, MetadataField.SEARCH_QUALITY_METADATA,
                request.getReference(), contentType);
        if (StringUtils.isNotBlank(quality)) {
            if (isDouble(quality)) {
                itemMetadata.setSearchQualityMetadata(
                        new SearchQualityMetadata()
                                .setQuality(Double.valueOf(quality)));
            } else {
                LOG.warn(
                        "Ignoring non-numeric searchQualityMetadata "
                                + "value: {}",
                        quality);
            }
        }
        return itemMetadata;
    }

    private String resolveMetadataValue(
            Properties metadata,
            MetadataField field,
            String reference,
            String contentType) {
        var mapping = findMetadataMapping(field);
        if (mapping != null) {
            var value = metadataValue(metadata, mapping.getFromField());
            value = StringUtils.defaultIfBlank(value,
                    mapping.getDefaultValue());
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }

        return switch (field) {
            case TITLE -> metadataValue(
                    metadata,
                    GoogleCloudSearchCommitterConfig.DEFAULT_TITLE_SOURCE_FIELD);
            case OBJECT_TYPE -> StringUtils.defaultIfBlank(
                    metadataValue(
                            metadata,
                            GoogleCloudSearchCommitterConfig.DEFAULT_OBJECT_TYPE_SOURCE_FIELD),
                    GoogleCloudSearchCommitterConfig.DEFAULT_OBJECT_TYPE);
            case MIME_TYPE -> contentType;
            case UPDATE_TIME -> metadataValue(
                    metadata,
                    GoogleCloudSearchCommitterConfig.DEFAULT_UPDATE_TIME_SOURCE_FIELD);
            case CREATE_TIME, CONTAINER_NAME, CONTENT_LANGUAGE,
                    HASH, SEARCH_QUALITY_METADATA ->
                    null;
            case SOURCE_REPOSITORY_URL -> reference;
            case KEYWORDS -> null;
        };
    }

    private List<String> resolveMetadataValues(
            Properties metadata, MetadataField field) {
        var mapping = findMetadataMapping(field);
        if (mapping == null) {
            return null;
        }
        var values = metadata.getStrings(mapping.getFromField());
        if (values != null && !values.isEmpty()) {
            return values;
        }
        if (StringUtils.isNotBlank(mapping.getDefaultValue())) {
            return Collections.singletonList(mapping.getDefaultValue());
        }
        return null;
    }

    private MetadataMapping findMetadataMapping(MetadataField targetField) {
        for (var mapping : config.getMetadataMappings()) {
            if (mapping == null || mapping.getToField() == null) {
                continue;
            }
            if (mapping.getToField() == targetField) {
                return mapping;
            }
        }
        return null;
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
            properties.add(toNamedProperty(entry.getKey(), entry.getValue()));
        }
        if (properties.isEmpty()) {
            return null;
        }
        return new ItemStructuredData().setObject(
                new StructuredDataObject().setProperties(properties));
    }

    private NamedProperty toNamedProperty(String name, List<String> values) {
        var property = new NamedProperty().setName(name);
        var type = findStructuredDataType(name);

        switch (type) {
            case DATE -> {
                var dates = parseAllDates(values);
                if (dates != null) {
                    return property.setDateValues(
                            new DateValues().setValues(dates));
                }
                LOG.warn(
                        "Field '{}' is mapped as structured data type "
                                + "'date' but not all values could be "
                                + "parsed as a date. Sending as text "
                                + "instead: {}",
                        name, values);
            }
            case TIMESTAMP -> {
                if (allMatch(values, this::isRfc3339Timestamp)) {
                    return property.setTimestampValues(new TimestampValues()
                            .setValues(new ArrayList<>(values)));
                }
                LOG.warn(
                        "Field '{}' is mapped as structured data type "
                                + "'timestamp' but not all values could be "
                                + "parsed as one. Sending as text instead: "
                                + "{}",
                        name, values);
            }
            case INTEGER -> {
                if (allMatch(values, this::isLong)) {
                    return property.setIntegerValues(
                            new IntegerValues().setValues(toLongs(values)));
                }
                LOG.warn(
                        "Field '{}' is mapped as structured data type "
                                + "'integer' but not all values could be "
                                + "parsed as one. Sending as text instead: "
                                + "{}",
                        name, values);
            }
            case DOUBLE -> {
                if (allMatch(values, this::isDouble)) {
                    return property.setDoubleValues(
                            new DoubleValues().setValues(toDoubles(values)));
                }
                LOG.warn(
                        "Field '{}' is mapped as structured data type "
                                + "'double' but not all values could be "
                                + "parsed as one. Sending as text instead: "
                                + "{}",
                        name, values);
            }
            case BOOLEAN -> {
                var bool = toBoolean(values.get(0));
                if (bool != null) {
                    return property.setBooleanValue(bool);
                }
                LOG.warn(
                        "Field '{}' is mapped as structured data type "
                                + "'boolean' but its value could not be "
                                + "parsed as one. Sending as text instead: "
                                + "{}",
                        name, values);
            }
            case ENUM -> {
                // Google Cloud Search caps the number of values a single
                // enum property can carry per item. Fields exceeding it
                // (e.g., a repeatable tag/keyword field) must fall back to
                // text rather than fail the whole batch.
                if (values.size() <= MAX_ENUM_VALUES) {
                    return property.setEnumValues(new EnumValues()
                            .setValues(new ArrayList<>(values)));
                }
                LOG.warn(
                        "Field '{}' is mapped as structured data type "
                                + "'enum' but has {} values, exceeding "
                                + "Google Cloud Search's limit of {}. "
                                + "Sending as text instead.",
                        name, values.size(), MAX_ENUM_VALUES);
            }
            case HTML -> {
                return property.setHtmlValues(
                        new HtmlValues().setValues(new ArrayList<>(values)));
            }
            case TEXT -> {
                // Fall through to text below.
            }
        }
        return property.setTextValues(
                new TextValues().setValues(new ArrayList<>(values)));
    }

    private StructuredDataType findStructuredDataType(String field) {
        for (var mapping : config.getStructuredDataMappings()) {
            if (mapping != null
                    && java.util.Objects.equals(mapping.getField(), field)) {
                return mapping.getType();
            }
        }
        return StructuredDataType.TEXT;
    }

    private Boolean toBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private boolean allMatch(
            List<String> values,
            java.util.function.Predicate<String> predicate) {
        for (String value : values) {
            if (StringUtils.isBlank(value) || !predicate.test(value)) {
                return false;
            }
        }
        return !values.isEmpty();
    }

    private List<Long> toLongs(List<String> values) {
        List<Long> longs = new ArrayList<>(values.size());
        for (String value : values) {
            longs.add(Long.valueOf(value));
        }
        return longs;
    }

    private List<Double> toDoubles(List<String> values) {
        List<Double> doubles = new ArrayList<>(values.size());
        for (String value : values) {
            doubles.add(Double.valueOf(value));
        }
        return doubles;
    }

    private List<Date> parseAllDates(List<String> values) {
        List<Date> dates = new ArrayList<>(values.size());
        for (String value : values) {
            var date = toDateValue(value);
            if (date == null) {
                return null;
            }
            dates.add(date);
        }
        return dates;
    }

    private Date toDateValue(String value) {
        try {
            var date = LocalDate.parse(value);
            return new Date()
                    .setYear(date.getYear())
                    .setMonth(date.getMonthValue())
                    .setDay(date.getDayOfMonth());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private boolean isLong(String value) {
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isDouble(String value) {
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isRfc3339Timestamp(String value) {
        try {
            Instant.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            try {
                OffsetDateTime.parse(value);
                return true;
            } catch (DateTimeParseException ex) {
                return false;
            }
        }
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
        for (MetadataMapping mapping : config.getMetadataMappings()) {
            if (mapping != null
                    && !mapping.isKeepFromField()
                    && StringUtils.isNotBlank(mapping.getFromField())) {
                excludedFields.add(mapping.getFromField());
            }
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
                var principal = toPrincipal(mapping.getPrincipalType(), value);
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

        var acl = new ItemAcl();
        if (!readers.isEmpty()) {
            acl.setReaders(readers);
        } else if (!hasInheritance) {
            // Cloud Search rejects items with no ACL at all ("Missing Acl in
            // request"), and inheritance alone does not grant access unless a
            // parent is set. When no reader mapping resolved to a value and
            // there is no ACL to inherit from, default to the entire domain
            // being able to read the item, matching the original Google-built
            // Norconex connector's documented default of granting read access
            // to everyone when no ACL information is supplied.
            acl.setReaders(Collections.singletonList(
                    toPrincipal(PrincipalType.CUSTOMER, null)));
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
        // The real Cloud Search media.upload endpoint returns an empty body
        // on success (unlike the Media-typed response the client stub
        // declares), so executeUnparsed() is used to avoid failing to parse
        // an empty response as JSON. The Media result isn't needed here.
        uploadRequest.executeUnparsed().disconnect();

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
        // Both parts are zero-padded to a fixed width so versions keep sorting
        // correctly as raw bytes, which is how Cloud Search compares them. The
        // sequence is only a tie-breaker within a same-millisecond burst, but
        // it is padded wide enough that it never grows a digit and inverts the
        // ordering on crawls of more than a million documents.
        return String.format("%019d-%012d", millis, sequence);
    }

    /**
     * Encodes a version string the way Cloud Search expects it on the wire.
     * Both {@code Item.version} and the delete request's {@code version} query
     * parameter are bytes fields, so they must carry base64 rather than the
     * raw string. URL-safe base64 is used because the value also travels as a
     * query parameter on deletes.
     * @param version raw version string
     * @return base64-encoded version
     */
    String encodeVersion(String version) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(version.getBytes(UTF_8));
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

    /**
     * Accumulates the failures reported by a batch. Google invokes the
     * callback registered with each queued request, so every outcome can be
     * attributed to the document that produced it instead of being reported
     * as an anonymous error.
     */
    static final class BatchFailureCollector {
        private static final int NOT_FOUND = 404;

        private final List<String> failures = new ArrayList<>();

        /**
         * Creates the callback for a single queued operation.
         * @param operation operation name, for reporting
         * @param reference document reference the operation applies to
         * @param tolerateNotFound whether a "404" is an acceptable outcome
         * @return callback to register with the batch request
         */
        JsonBatchCallback<Operation> callbackFor(
                String operation, String reference,
                boolean tolerateNotFound) {
            return new JsonBatchCallback<Operation>() {
                @Override
                public void onSuccess(Operation op, HttpHeaders headers) {
                    // NOOP
                }

                @Override
                public void onFailure(
                        GoogleJsonError e, HttpHeaders headers) {
                    if (tolerateNotFound && e.getCode() == NOT_FOUND) {
                        LOG.debug("Ignoring \"not found\" response for {} of "
                                + "\"{}\": the item is already absent from "
                                + "the index.", operation, reference);
                        return;
                    }
                    var detail = describe(e);
                    LOG.error("Google Cloud Search rejected the {} of "
                            + "\"{}\": {}", operation, reference, detail);
                    failures.add(String.format(
                            "%s of \"%s\": %s", operation, reference, detail));
                }
            };
        }

        private static String describe(GoogleJsonError e) {
            try {
                return e.toPrettyString();
            } catch (IOException ioe) {
                return String.valueOf(e);
            }
        }

        void throwIfAny() throws CommitterException {
            if (!failures.isEmpty()) {
                throw new CommitterException(
                        "Google Cloud Search returned " + failures.size()
                                + " batch failure(s):\n"
                                + StringUtils.join(failures, "\n"));
            }
        }
    }
}
