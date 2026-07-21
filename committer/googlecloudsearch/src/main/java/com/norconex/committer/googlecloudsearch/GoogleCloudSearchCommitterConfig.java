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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.norconex.committer.core.batch.BaseBatchCommitterConfig;
import com.norconex.commons.lang.bean.jackson.JsonXmlCollection;
import com.norconex.commons.lang.collection.CollectionUtil;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * Google Cloud Search Committer configuration.
 * </p>
 */
@Data
@Accessors(chain = true)
public class GoogleCloudSearchCommitterConfig
        extends BaseBatchCommitterConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Default Google Cloud Search committer application name. */
    public static final String DEFAULT_APPLICATION_NAME =
            "Norconex Google Cloud Search Committer";
    /** Default document title metadata source field. */
    public static final String DEFAULT_TITLE_SOURCE_FIELD = "title";
    /** Default document object type metadata source field. */
    public static final String DEFAULT_OBJECT_TYPE_SOURCE_FIELD = "objectType";
    /** Default object type value when no metadata value is provided. */
    public static final String DEFAULT_OBJECT_TYPE = "document";
    /** Default update time metadata source field. */
    public static final String DEFAULT_UPDATE_TIME_SOURCE_FIELD =
            "Last-Modified";

    /** Format used to upload document content. */
    public enum UploadFormat {
        /**
         * Upload the original, unparsed binary content (captured in the
         * {@value GoogleCloudSearchClient#FIELD_BINARY_CONTENT} metadata
         * field, e.g., by a {@code BinaryContentTransformer} pre-parse
         * handler).
         */
        RAW,
        /** Upload the extracted plain-text content. */
        TEXT
    }

    /** Priority/consistency mode used for indexing and delete requests. */
    public enum RequestMode {
        /**
         * Higher-priority, latency-sensitive mode, rate-limited more
         * strictly by Google Cloud Search. Intended for interactive,
         * user-facing requests.
         */
        SYNCHRONOUS,
        /**
         * Default mode for content connectors performing full or
         * incremental crawls, offering higher throughput.
         */
        ASYNCHRONOUS
    }

    /** Target of an ACL mapping. */
    public enum AclTarget {
        READERS, DENIED_READERS, OWNERS
    }

    /** Type of principal an ACL mapping resolves to. */
    public enum PrincipalType {
        USER, GROUP, CUSTOMER
    }

    /** How ACLs are inherited from a parent item. */
    public enum AclInheritanceType {
        NOT_APPLICABLE, CHILD_OVERRIDE, PARENT_OVERRIDE, BOTH_PERMIT
    }

    /**
     * Supported Google Cloud Search item metadata targets.
     *
     * See Google reference:
     * https://developers.google.com/workspace/cloud-search/docs/reference/rest/v1/ItemMetadata
     */
    public enum MetadataField {
        TITLE("title"),
        OBJECT_TYPE("objectType"),
        MIME_TYPE("mimeType"),
        UPDATE_TIME("updateTime"),
        CREATE_TIME("createTime"),
        CONTAINER_NAME("containerName"),
        CONTENT_LANGUAGE("contentLanguage"),
        SOURCE_REPOSITORY_URL("sourceRepositoryUrl");

        private final String value;

        MetadataField(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static MetadataField fromValue(String value) {
            if (value == null) {
                return null;
            }
            for (var field : values()) {
                if (field.value.equalsIgnoreCase(value)) {
                    return field;
                }
            }
            return null;
        }
    }

    /**
     * Path to the Google service account JSON key file, with access to
     * the Cloud Search indexing API.
     */
    private String secretKeyPath;

    /**
     * The Google Cloud Search data source ID to index documents into.
     */
    private String dataSourceId;

    /**
     * The format used to upload document content. Default is
     * {@link UploadFormat#RAW}.
     */
    private UploadFormat uploadFormat = UploadFormat.RAW;

    /**
     * The priority/consistency mode sent with indexing and delete requests.
     * Default is {@link RequestMode#ASYNCHRONOUS}, suited to content
     * connectors performing full or incremental crawls.
     */
    private RequestMode requestMode = RequestMode.ASYNCHRONOUS;

    /**
     * Overrides the Google Cloud Search API root URL. Mainly useful for
     * testing against a local mock server.
     */
    private String apiEndpoint;

    /**
     * The application name sent to Google Cloud Search. Default is
     * {@value #DEFAULT_APPLICATION_NAME}.
     */
    private String applicationName = DEFAULT_APPLICATION_NAME;

    /**
     * The connector name sent with indexing requests. Defaults to the
     * application name when not set.
     */
    private String connectorName;

    /**
     * Metadata field holding the value to use as the document unique
     * identifier. Defaults to the document reference.
     */
    private String sourceIdField;

    /**
     * Whether to keep the source ID field in the document metadata after
     * it has been extracted.
     */
    private boolean keepSourceIdField;

    /**
     * Metadata mappings to populate Google Cloud Search predefined metadata
     * fields. Mapped source fields are excluded from structured data unless
     * {@code keepFromField} is set to {@code true}.
     *
     * See Google reference:
     * https://developers.google.com/workspace/cloud-search/docs/reference/rest/v1/ItemMetadata
     */
    @JsonXmlCollection(entryName = "mapping")
    private final List<MetadataMapping> metadataMappings = new ArrayList<>();

    /**
     * Whether to infer typed structured-data values (date, timestamp,
     * integer, double, enum) instead of always sending text values.
     */
    private boolean typedStructuredData;

    /**
     * Mappings of metadata fields to Google Cloud Search ACL principals.
     * Cloud Search requires every indexed item to carry an ACL. When no
     * mapping resolves to a reader for a given document (or none is
     * configured at all) and no ACL is being inherited, the item defaults
     * to being readable by the entire Google Workspace domain.
     */
    @JsonXmlCollection(entryName = "mapping")
    private final List<AclMapping> aclMappings = new ArrayList<>();

    /**
     * ACL inheritance configuration, mapping a metadata field holding a
     * parent item reference to an ACL inheritance type.
     */
    private AclInheritanceMapping aclInheritance = new AclInheritanceMapping();

    public List<AclMapping> getAclMappings() {
        return Collections.unmodifiableList(aclMappings);
    }

    public GoogleCloudSearchCommitterConfig setAclMappings(
            List<AclMapping> aclMappings) {
        CollectionUtil.setAll(this.aclMappings, aclMappings);
        return this;
    }

    public List<MetadataMapping> getMetadataMappings() {
        return Collections.unmodifiableList(metadataMappings);
    }

    public GoogleCloudSearchCommitterConfig setMetadataMappings(
            List<MetadataMapping> metadataMappings) {
        CollectionUtil.setAll(this.metadataMappings, metadataMappings);
        return this;
    }

    /**
     * Maps a metadata field to a Google Cloud Search ACL principal.
     */
    @Data
    @Accessors(chain = true)
    public static class AclMapping implements Serializable {
        private static final long serialVersionUID = 1L;

        /** Metadata field holding the principal value(s). */
        private String fromField;
        /** Which ACL list the resolved principal(s) are added to. */
        private AclTarget target;
        /** Type of principal the field value represents. */
        private PrincipalType principalType = PrincipalType.USER;
    }

    /**
     * Declares which metadata field holds a parent item reference to use
     * for Google Cloud Search ACL inheritance.
     */
    @Data
    @Accessors(chain = true)
    public static class AclInheritanceMapping implements Serializable {
        private static final long serialVersionUID = 1L;

        /** Metadata field holding the parent item reference. */
        private String fromField;
        /**
         * ACL inheritance type. Default is {@link AclInheritanceType#NOT_APPLICABLE}.
         */
        private AclInheritanceType aclInheritanceType =
                AclInheritanceType.NOT_APPLICABLE;
    }

    /**
     * Maps crawler metadata fields to Google Cloud Search predefined
     * metadata fields.
     *
     * See Google reference:
     * https://developers.google.com/workspace/cloud-search/docs/reference/rest/v1/ItemMetadata
     */
    @Data
    @Accessors(chain = true)
    public static class MetadataMapping implements Serializable {
        private static final long serialVersionUID = 1L;

        /** Optional source metadata field in crawler documents. */
        private String fromField;
        /** Mandatory target Google Cloud Search metadata field. */
        private String toField;
        /**
         * Optional fallback value when {@link #fromField} is missing or blank.
         */
        private String defaultValue;
        /**
         * Whether to keep {@link #fromField} in structured data.
         */
        private boolean keepFromField;
    }
}
