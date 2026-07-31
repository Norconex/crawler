/* Copyright 2015-2020 Norconex Inc.
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
package com.norconex.collector.http.fetch.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.apache.http.auth.AuthScope;
import org.apache.http.auth.NTCredentials;
import org.junit.jupiter.api.Test;

import com.norconex.commons.lang.security.Credentials;
import com.norconex.commons.lang.xml.XML;

public class GenericHttpFetcherTest  {

    @Test
    public void testWriteRead() {
        GenericHttpFetcherConfig cfg = new GenericHttpFetcherConfig();
        cfg.setValidStatusCodes(200, 201, 202);
        cfg.setNotFoundStatusCodes(404, 405);
        cfg.setHeadersPrefix("blah");
        cfg.setForceCharsetDetection(true);
        cfg.setForceContentTypeDetection(true);

        GenericHttpFetcher f = new GenericHttpFetcher(cfg);
        XML.assertWriteRead(f, "fetcher");
    }

    // Regression test for https://github.com/Norconex/crawler/issues/1302:
    // NTLM (and Basic/Digest) credentials must still be registered when no
    // <host> is configured, applying to "any host" as documented on
    // HttpAuthConfig#getHost().
    @Test
    public void testNtlmCredentialsWithoutHost() {
        var authConfig = new HttpAuthConfig();
        authConfig.setMethod(GenericHttpFetcher.AUTH_METHOD_NTLM);
        authConfig.setCredentials(
                new Credentials().setUsername("joeUser")
                        .setPassword("joePassword"));
        authConfig.setDomain("DOMAIN");
        // host intentionally left unset, as it would be when a user leaves
        // <host/> empty in XML.

        var cfg = new GenericHttpFetcherConfig();
        cfg.setAuthConfig(authConfig);

        var fetcher = new GenericHttpFetcher(cfg);
        var credsProvider = fetcher.createCredentialsProvider();

        assertNotNull(credsProvider);
        var creds = credsProvider.getCredentials(new AuthScope(
                "intranet.example.com", 443, AuthScope.ANY_REALM,
                GenericHttpFetcher.AUTH_METHOD_NTLM));
        assertInstanceOf(NTCredentials.class, creds);
        assertEquals("DOMAIN\\joeUser", creds.getUserPrincipal().getName());
    }
}
