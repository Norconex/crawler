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

import java.util.LinkedHashMap;
import java.util.Map;

import com.norconex.crawler.core.fetch.BaseFetcherConfig;

import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * Configuration scaffold for a future {@link GoogleDriveFetcher}
 * implementation.
 */
@Data
@Accessors(chain = true)
public class GoogleDriveFetcherConfig extends BaseFetcherConfig {

    public enum NativeDocumentFormatPolicy {
        TEXT,
        PDF,
        OOXML
    }

    public static final Map<String, String> DEFAULT_TEXT_EXPORT_MIME_TYPES =
            Map.of(
                    "application/vnd.google-apps.document", "text/plain",
                    "application/vnd.google-apps.spreadsheet", "text/csv",
                    "application/vnd.google-apps.presentation",
                    "text/plain");

    public static final Map<String, String> DEFAULT_PDF_EXPORT_MIME_TYPES =
            Map.of(
                    "application/vnd.google-apps.document", "application/pdf",
                    "application/vnd.google-apps.spreadsheet",
                    "application/pdf",
                    "application/vnd.google-apps.presentation",
                    "application/pdf");

    public static final Map<String, String> DEFAULT_OOXML_EXPORT_MIME_TYPES =
            Map.of(
                    "application/vnd.google-apps.document",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.google-apps.spreadsheet",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.google-apps.presentation",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    /**
     * Service account client email.
     */
    private String clientEmail;

    /**
     * PEM private key content or secure key reference.
     */
    @ToString.Exclude
    private String privateKey;

    /**
     * Optional delegated Google Workspace user email for domain-wide
     * delegation.
     */
    private String delegatedUser;

    /**
     * Optional Google API application name.
     */
    private String applicationName;

    /**
     * Default export policy for Google-native document families when no
     * explicit override is configured.
     */
    private NativeDocumentFormatPolicy nativeDocumentFormatPolicy =
            NativeDocumentFormatPolicy.TEXT;

    /**
     * Explicit export overrides for Google-native document mime types.
     *
     * Key: source mime type (e.g. application/vnd.google-apps.document)
     * Value: export mime type (e.g. text/plain)
     */
    private final Map<String, String> exportMimeTypeMap =
            new LinkedHashMap<>();

    public Map<String, String> defaultExportMimeTypes() {
        return switch (nativeDocumentFormatPolicy) {
            case PDF -> DEFAULT_PDF_EXPORT_MIME_TYPES;
            case OOXML -> DEFAULT_OOXML_EXPORT_MIME_TYPES;
            case TEXT -> DEFAULT_TEXT_EXPORT_MIME_TYPES;
        };
    }
}
