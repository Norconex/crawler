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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Supported Google Cloud Search item metadata targets.
 *
 * See Google reference:
 * https://developers.google.com/workspace/cloud-search/docs/reference/rest/v1/indexing.datasources.items#ItemMetadata
 */
public enum MetadataField {
    TITLE("title"),
    OBJECT_TYPE("objectType"),
    MIME_TYPE("mimeType"),
    UPDATE_TIME("updateTime"),
    CREATE_TIME("createTime"),
    CONTAINER_NAME("containerName"),
    CONTENT_LANGUAGE("contentLanguage"),
    SOURCE_REPOSITORY_URL("sourceRepositoryUrl"),
    HASH("hash"),
    KEYWORDS("keywords"),
    SEARCH_QUALITY_METADATA("searchQualityMetadata");

    private final String fieldName;

    MetadataField(String fieldName) {
        this.fieldName = fieldName;
    }

    @JsonValue
    @Override
    public String toString() {
        return fieldName;
    }

    @JsonCreator
    public static MetadataField fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (var field : values()) {
            if (field.fieldName.equalsIgnoreCase(value)) {
                return field;
            }
        }
        return null;
    }
}
