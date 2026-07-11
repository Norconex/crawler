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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.hc.core5.http.HttpStatus;

import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.crawler.core.fetch.BaseFetcherConfig;

import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * Configuration for {@link M365GraphFetcher}.
 */
@Data
@Accessors(chain = true)
public class M365GraphFetcherConfig extends BaseFetcherConfig {

    public enum SourceDeltaExpansion {
        /**
         * Keep the start reference itself as the source-delta root.
         * For M365 this is only valid on drive references.
         */
        SELF_ONLY,
        /**
         * Expand broader start references such as site, site URL, or user
         * into child drives and maintain one delta cursor per drive.
         */
        INCLUDE_CHILD_DRIVES
    }

    public static final List<Integer> DEFAULT_VALID_STATUS_CODES =
            CollectionUtil.unmodifiableList(
                    HttpStatus.SC_OK,
                    HttpStatus.SC_CREATED,
                    HttpStatus.SC_ACCEPTED,
                    HttpStatus.SC_NO_CONTENT);

    public static final List<Integer> DEFAULT_NOT_FOUND_STATUS_CODES =
            CollectionUtil.unmodifiableList(HttpStatus.SC_NOT_FOUND);

    public static final List<Integer> DEFAULT_NATIVE_RETRY_STATUS_CODES =
            CollectionUtil.unmodifiableList(
                    HttpStatus.SC_TOO_MANY_REQUESTS,
                    HttpStatus.SC_SERVICE_UNAVAILABLE,
                    HttpStatus.SC_GATEWAY_TIMEOUT);

    public static final Duration DEFAULT_NATIVE_RETRY_BASE_DELAY =
            Duration.ofSeconds(1);

    /**
     * Tenant ID used to acquire an access token.
     */
    private String tenantId;

    /**
     * Azure AD application (client) ID.
     */
    private String clientId;

    /**
     * Azure AD client secret.
     */
    @ToString.Exclude
    private String clientSecret;

    /**
     * Authentication authority host.
     */
    private String authorityHost = "https://login.microsoftonline.com";

    /**
     * Microsoft Graph v1 endpoint root.
     */
    private String graphBaseUrl = "https://graph.microsoft.com/v1.0";

    /**
     * How SOURCE_DELTA start references are expanded into Graph delta roots.
     */
    private SourceDeltaExpansion sourceDeltaExpansion =
            SourceDeltaExpansion.SELF_ONLY;

    /**
     * HTTP status codes considered successful for Graph API calls.
     */
    private final List<Integer> validStatusCodes =
            new ArrayList<>(DEFAULT_VALID_STATUS_CODES);

    /**
     * HTTP status codes considered "not found".
     */
    private final List<Integer> notFoundStatusCodes =
            new ArrayList<>(DEFAULT_NOT_FOUND_STATUS_CODES);

    /**
     * Whether to apply M365-native retry for throttling/transient statuses.
     * Disable to rely strictly on framework-level fetch retries.
     */
    private boolean nativeRetryEnabled;

    /**
     * Maximum native retries for status codes listed in
     * {@link #nativeRetryStatusCodes}.
     */
    private int nativeRetryMaxRetries = 3;

    /**
     * Base delay for native retries when Retry-After is not provided.
     */
    private Duration nativeRetryBaseDelay = DEFAULT_NATIVE_RETRY_BASE_DELAY;

    /**
     * Status codes eligible for native retry when enabled.
     */
    private final List<Integer> nativeRetryStatusCodes =
            new ArrayList<>(DEFAULT_NATIVE_RETRY_STATUS_CODES);

    public List<Integer> getValidStatusCodes() {
        return Collections.unmodifiableList(validStatusCodes);
    }

    public M365GraphFetcherConfig setValidStatusCodes(
            List<Integer> validStatusCodes) {
        CollectionUtil.setAll(this.validStatusCodes, validStatusCodes);
        return this;
    }

    public List<Integer> getNotFoundStatusCodes() {
        return Collections.unmodifiableList(notFoundStatusCodes);
    }

    public M365GraphFetcherConfig setNotFoundStatusCodes(
            List<Integer> notFoundStatusCodes) {
        CollectionUtil.setAll(this.notFoundStatusCodes, notFoundStatusCodes);
        return this;
    }

    public List<Integer> getNativeRetryStatusCodes() {
        return Collections.unmodifiableList(nativeRetryStatusCodes);
    }

    public M365GraphFetcherConfig setNativeRetryStatusCodes(
            List<Integer> nativeRetryStatusCodes) {
        CollectionUtil.setAll(
                this.nativeRetryStatusCodes,
                nativeRetryStatusCodes);
        return this;
    }
}
