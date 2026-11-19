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
package com.norconex.crawler.fs.fetch.impl;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.encrypt.EncryptionUtil;
import com.norconex.commons.lang.security.Credentials;

/**
 * <p>
 * Reads the secrets of fetchers that authenticate with a token, a key or a
 * client secret, which were plain text configuration properties: the
 * value can also be given as {@link Credentials}, whose password can be
 * encrypted (and so kept out of the configuration, or supplied as a
 * variable).
 * </p>
 * <p>
 * A value set on the property itself always wins, so existing configurations
 * are read as they were.
 * </p>
 */
public final class FetcherCredentials {

    private FetcherCredentials() {
    }

    /**
     * The secret: the configured value if there is one, otherwise the
     * (decrypted) password of the credentials.
     * @param configured the value of the plain property, possibly blank
     * @param credentials the credentials, possibly not set
     * @return the secret, or the configured value (blank or {@code null}) when
     *         there is no other
     */
    public static String secret(String configured, Credentials credentials) {
        if (StringUtils.isNotBlank(configured)) {
            return configured;
        }
        if (credentials != null
                && StringUtils.isNotBlank(credentials.getPassword())) {
            return EncryptionUtil.decryptPassword(credentials);
        }
        return configured;
    }

    /**
     * The identifier that goes with the secret (a client email, a client ID):
     * the configured value if there is one, otherwise the username of the
     * credentials.
     * @param configured the value of the plain property, possibly blank
     * @param credentials the credentials, possibly not set
     * @return the identifier, or the configured value (blank or {@code null})
     *         when there is no other
     */
    public static String id(String configured, Credentials credentials) {
        if (StringUtils.isNotBlank(configured)) {
            return configured;
        }
        if (credentials != null
                && StringUtils.isNotBlank(credentials.getUsername())) {
            return credentials.getUsername();
        }
        return configured;
    }
}
