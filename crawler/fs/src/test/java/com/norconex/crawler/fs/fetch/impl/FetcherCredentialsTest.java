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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.norconex.commons.lang.encrypt.EncryptionKey;
import com.norconex.commons.lang.encrypt.EncryptionUtil;
import com.norconex.commons.lang.security.Credentials;

class FetcherCredentialsTest {

    @Test
    void theConfiguredValueWinsOverTheCredentials() {
        var creds = new Credentials().setUsername("user").setPassword("pass");
        assertThat(FetcherCredentials.secret("configured", creds))
                .isEqualTo("configured");
        assertThat(FetcherCredentials.id("configured", creds))
                .isEqualTo("configured");
    }

    @Test
    void theCredentialsAreUsedWhenTheConfiguredValueIsBlank() {
        var creds = new Credentials().setUsername("user").setPassword("pass");
        assertThat(FetcherCredentials.secret(null, creds)).isEqualTo("pass");
        assertThat(FetcherCredentials.secret(" ", creds)).isEqualTo("pass");
        assertThat(FetcherCredentials.id(null, creds)).isEqualTo("user");
        assertThat(FetcherCredentials.id("", creds)).isEqualTo("user");
    }

    @Test
    void anEncryptedPasswordIsDecrypted() {
        var key = new EncryptionKey("a key only this test knows");
        var creds = new Credentials()
                .setPassword(EncryptionUtil.encrypt("secret", key))
                .setPasswordKey(key);
        assertThat(FetcherCredentials.secret(null, creds))
                .isEqualTo("secret");
    }

    @Test
    void nothingToUseGivesBackTheConfiguredValue() {
        assertThat(FetcherCredentials.secret(null, new Credentials()))
                .isNull();
        assertThat(FetcherCredentials.secret("", new Credentials())).isEmpty();
        assertThat(FetcherCredentials.secret(null, null)).isNull();
        assertThat(FetcherCredentials.id(null, new Credentials())).isNull();
        assertThat(FetcherCredentials.id(null, null)).isNull();
    }
}
