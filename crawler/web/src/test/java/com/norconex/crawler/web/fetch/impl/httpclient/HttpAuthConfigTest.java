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
package com.norconex.crawler.web.fetch.impl.httpclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.norconex.commons.lang.security.Credentials;

class HttpAuthConfigTest {

    @Test
    void testSetCredentialsCopiesValues() {
        var source = new Credentials("userA", "passA");
        var cfg = new HttpAuthConfig();

        cfg.setCredentials(source);
        source.setUsername("changed").setPassword("changed");

        assertThat(cfg.getCredentials().getUsername()).isEqualTo("userA");
        assertThat(cfg.getCredentials().getPassword()).isEqualTo("passA");
    }

    @Test
    void testFormParamsDefensiveCopyAndNames() {
        var cfg = new HttpAuthConfig();
        cfg.setFormParam("single", "1");
        assertThat(cfg.getFormParam("single")).isEqualTo("1");

        cfg.setFormParams(Map.of("alpha", "A", "beta", "B"));
        assertThat(cfg.getFormParam("alpha")).isEqualTo("A");
        assertThat(cfg.getFormParam("single")).isNull();

        var params = cfg.getFormParams();
        params.put("gamma", "C");
        assertThat(cfg.getFormParam("gamma")).isNull();

        assertThat(cfg.getFormParamNames()).containsExactlyInAnyOrder(
                "alpha", "beta");
        assertThatThrownBy(() -> cfg.getFormParamNames().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
