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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.norconex.crawler.fs.fetch.impl.box.BoxFetcherConfig.AuthMethod;
import com.sun.net.httpserver.HttpServer;

class BoxFetcherClientCredentialsTest {

    private HttpServer server;
    private final List<String> forms = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth2/token", exchange -> {
            forms.add(new String(exchange.getRequestBody().readAllBytes(),
                    UTF_8));
            var body = ("{\"access_token\":\"token-" + forms.size()
                    + "\",\"expires_in\":" + 3600 + "}")
                            .getBytes(UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private BoxFetcher fetcher() {
        var f = new BoxFetcher();
        var cfg = f.getConfiguration();
        cfg.setAuthMethod(AuthMethod.CLIENT_CREDENTIALS);
        cfg.setEnterpriseId("123456");
        cfg.setTokenUrl("http://127.0.0.1:" + server.getAddress().getPort()
                + "/oauth2/token");
        cfg.getCredentials().setUsername("the-client-id");
        cfg.getCredentials().setPassword("the-client-secret");
        return f;
    }

    @Test
    void theApplicationAsksForATokenAsTheEnterpriseAndUsesItAsABearer() {
        assertThat(fetcher().authorization()).isEqualTo("Bearer token-1");
        assertThat(forms).singleElement().satisfies(form -> assertThat(form)
                .contains("grant_type=client_credentials")
                .contains("client_id=the-client-id")
                .contains("client_secret=the-client-secret")
                .contains("box_subject_type=enterprise")
                .contains("box_subject_id=123456"));
    }

    @Test
    void aTokenThatHasNotExpiredIsReused() {
        var f = fetcher();
        f.authorization();
        f.authorization();

        assertThat(forms).hasSize(1);
    }

    @Test
    void withoutAnEnterpriseThereIsNothingToAskFor() {
        var f = fetcher();
        f.getConfiguration().setEnterpriseId(null);

        assertThatThrownBy(f::authorization)
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("enterprise ID");
        assertThat(forms).isEmpty();
    }
}
