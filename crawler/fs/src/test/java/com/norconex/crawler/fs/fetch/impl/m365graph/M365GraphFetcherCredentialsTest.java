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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.junit.jupiter.api.Test;

class M365GraphFetcherCredentialsTest {

    @Test
    void clientIdAndSecretOfTheConfigurationAreUsed() throws IOException {
        var f = new M365GraphFetcher();
        f.getConfiguration().setTenantId("tenant")
                .setClientId("id-1").setClientSecret("secret-1");
        assertThat(f.tokenForm())
                .contains("client_id=id-1")
                .contains("client_secret=secret-1");
    }

    @Test
    void usernameAndPasswordOfTheCredentialsAreTheClientIdAndSecret()
            throws IOException {
        var f = new M365GraphFetcher();
        f.getConfiguration().setTenantId("tenant");
        f.getConfiguration().getCredentials()
                .setUsername("id-2").setPassword("secret-2");
        assertThat(f.tokenForm())
                .contains("client_id=id-2")
                .contains("client_secret=secret-2");
    }

    @Test
    void whatIsConfiguredWinsOverTheCredentials() throws IOException {
        var f = new M365GraphFetcher();
        f.getConfiguration().setTenantId("tenant")
                .setClientId("id-1").setClientSecret("secret-1");
        f.getConfiguration().getCredentials()
                .setUsername("id-2").setPassword("secret-2");
        assertThat(f.tokenForm())
                .contains("client_id=id-1")
                .contains("client_secret=secret-1");
    }

    @Test
    void aMissingClientSecretIsRefused() {
        var f = new M365GraphFetcher();
        f.getConfiguration().setTenantId("tenant").setClientId("id-1");
        assertThatThrownBy(f::tokenForm).isInstanceOf(IOException.class)
                .hasMessageContaining("clientSecret");
    }
}
