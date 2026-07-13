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
    /** Default document title metadata field. */
    public static final String DEFAULT_TITLE_FIELD = "title";
    /** Default document object type metadata field. */
    public static final String DEFAULT_OBJECT_TYPE_FIELD = "objectType";
    /** Default update time metadata field. */
    public static final String DEFAULT_UPDATE_TIME_FIELD = "Last-Modified";

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
     * Metadata field mapped to the Google Cloud Search item title.
     * Default is {@value #DEFAULT_TITLE_FIELD}.
     */
    private String titleField = DEFAULT_TITLE_FIELD;

    /**
     * Metadata field mapped to the Google Cloud Search item object type.
     * Default is {@value #DEFAULT_OBJECT_TYPE_FIELD}.
     */
    private String objectTypeField = DEFAULT_OBJECT_TYPE_FIELD;

    /**
     * Metadata field mapped to the Google Cloud Search item update time.
     * Default is {@value #DEFAULT_UPDATE_TIME_FIELD}.
     */
    private String updateTimeField = DEFAULT_UPDATE_TIME_FIELD;

    /**
     * Metadata field mapped to the Google Cloud Search item container name.
     */
    private String containerNameField;

    /**
     * Metadata field mapped to the Google Cloud Search item content
     * language.
     */
    private String contentLanguageField;

    /**
     * Metadata field mapped to the Google Cloud Search item source
     * repository URL. Defaults to the document reference.
     */
    private String sourceRepositoryUrlField;

    /**
     * Mappings of metadata fields to Google Cloud Search ACL principals.
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
        /** ACL inheritance type. Default is {@link AclInheritanceType#NOT_APPLICABLE}. */
        private AclInheritanceType aclInheritanceType =
                AclInheritanceType.NOT_APPLICABLE;
    }
}
