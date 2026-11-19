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
package com.norconex.crawler.fs.fetch.impl.gcs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.NoCredentials;

/** How the Google Cloud Storage client is authenticated. */
class GcsFetcherCredentialsTest {

    private static String serviceAccountKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64,
                        "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(generator.generateKeyPair()
                                .getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        return """
                {
                  "type": "service_account",
                  "project_id": "my-project",
                  "private_key_id": "key1",
                  "private_key": "%s",
                  "client_email": "svc@my-project.iam.gserviceaccount.com",
                  "client_id": "123",
                  "token_uri": "https://oauth2.googleapis.com/token"
                }
                """.formatted(pem.replace("\n", "\\n"));
    }

    @Test
    void thePasswordOfTheCredentialsIsAServiceAccountKey() throws Exception {
        var f = new GcsFetcher();
        f.getConfiguration().getCredentials().setPassword(serviceAccountKey());

        var credentials = f.buildStorageOptions().getCredentials();

        assertThat(credentials).isInstanceOf(ServiceAccountCredentials.class);
        assertThat(((ServiceAccountCredentials) credentials).getClientEmail())
                .isEqualTo("svc@my-project.iam.gserviceaccount.com");
    }

    @Test
    void somethingThatIsNotAServiceAccountKeyIsRefused() {
        var f = new GcsFetcher();
        f.getConfiguration().getCredentials().setPassword("not json");

        assertThatThrownBy(f::buildStorageOptions)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service account key");
    }

    @Test
    void anEndpointMeansNoSignInWhateverTheCredentials() throws Exception {
        var f = new GcsFetcher();
        f.getConfiguration().setEndpoint("http://localhost:4443");
        f.getConfiguration().getCredentials().setPassword(serviceAccountKey());

        assertThat(f.buildStorageOptions().getCredentials())
                .isSameAs(NoCredentials.getInstance());
    }
}
