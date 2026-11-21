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
package com.norconex.crawler.fs.fetch.impl.box;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.hc.core5.http.HttpStatus;

import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.commons.lang.security.Credentials;
import com.norconex.crawler.core.fetch.BaseFetcherConfig;

import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * Configuration for {@link BoxFetcher}.
 */
@Data
@Accessors(chain = true)
public class BoxFetcherConfig extends BaseFetcherConfig {

    public static final List<Integer> DEFAULT_VALID_STATUS_CODES =
            CollectionUtil.unmodifiableList(
                    HttpStatus.SC_OK,
                    HttpStatus.SC_CREATED,
                    HttpStatus.SC_ACCEPTED,
                    HttpStatus.SC_NO_CONTENT);

    public static final List<Integer> DEFAULT_NOT_FOUND_STATUS_CODES =
            CollectionUtil.unmodifiableList(HttpStatus.SC_NOT_FOUND);

    /**
     * OAuth access token used for Box API requests, kept as is in the
     * configuration. To keep it secret (encrypted, or supplied as a
     * variable), leave this empty and give it as the password of the
     * {@link #getCredentials() credentials}.
     */
    /**
     * How the fetcher gets the access token it calls Box with.
     */
    public enum AuthMethod {
        /**
         * The access token given as {@code accessToken}, or as the password
         * of the credentials, is used as is. Box access tokens expire, so
         * this suits a token issued to last or a short test.
         */
        ACCESS_TOKEN,
        /**
         * A Box application using Client Credentials Grant: the credentials
         * hold its client ID (user name) and client secret (password), and
         * {@code enterpriseId} says which enterprise to act in. A new access
         * token is requested when the last one expires.
         */
        CLIENT_CREDENTIALS
    }

    private AuthMethod authMethod = AuthMethod.ACCESS_TOKEN;

    /**
     * The enterprise to act in. Required with
     * {@link AuthMethod#CLIENT_CREDENTIALS}.
     */
    private String enterpriseId;

    /**
     * The address Box answers token requests on, for
     * {@link AuthMethod#CLIENT_CREDENTIALS}. Change it only to go through a
     * proxy or a compatible server.
     */
    private String tokenUrl = "https://api.box.com/oauth2/token";

    @ToString.Exclude
    private String accessToken;

    /**
     * Credentials holding the OAuth access token as their password (the
     * username is not used). Used when {@link #getAccessToken()} is
     * empty. The password can be encrypted.
     */
    private final Credentials credentials = new Credentials();

    /**
     * Box API base URL.
     */
    private String apiBaseUrl = "https://api.box.com/2.0";

    /**
     * HTTP status codes considered successful for Box API calls.
     */
    private final List<Integer> validStatusCodes =
            new ArrayList<>(DEFAULT_VALID_STATUS_CODES);

    /**
     * HTTP status codes considered "not found".
     */
    private final List<Integer> notFoundStatusCodes =
            new ArrayList<>(DEFAULT_NOT_FOUND_STATUS_CODES);

    public List<Integer> getValidStatusCodes() {
        return Collections.unmodifiableList(validStatusCodes);
    }

    public BoxFetcherConfig
            setValidStatusCodes(List<Integer> validStatusCodes) {
        CollectionUtil.setAll(this.validStatusCodes, validStatusCodes);
        return this;
    }

    public List<Integer> getNotFoundStatusCodes() {
        return Collections.unmodifiableList(notFoundStatusCodes);
    }

    public BoxFetcherConfig setNotFoundStatusCodes(
            List<Integer> notFoundStatusCodes) {
        CollectionUtil.setAll(this.notFoundStatusCodes, notFoundStatusCodes);
        return this;
    }
}
