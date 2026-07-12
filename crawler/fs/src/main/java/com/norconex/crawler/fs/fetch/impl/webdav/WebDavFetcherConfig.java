/* Copyright 2023-2026 Norconex Inc.
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
package com.norconex.crawler.fs.fetch.impl.webdav;

import java.time.Duration;

import com.norconex.commons.lang.encrypt.EncryptionKey;
import com.norconex.commons.lang.net.ProxySettings;
import com.norconex.crawler.fs.fetch.impl.BaseAuthNioFetcherConfig;

import jakarta.xml.bind.annotation.XmlTransient;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * <p>
 * Configuration for {@link WebDavFetcher}.
 * </p>
 */
@Data
@Accessors(chain = true)
public class WebDavFetcherConfig extends BaseAuthNioFetcherConfig {

    //MAYBE ACL?

    private Duration connectionTimeout;
    private boolean followRedirect;
    private boolean hostnameVerificationEnabled;
    private boolean keepAlive;
    private String keyStoreFile;
    @ToString.Exclude
    private String keyStorePass;
    @XmlTransient
    private EncryptionKey keyStorePassKey;
    private String keyStoreType;
    private int maxConnectionsPerHost = 5;
    private int maxTotalConnections = 50;
    private boolean preemptiveAuth;
    @XmlTransient
    private final ProxySettings proxySettings = new ProxySettings();
    @XmlTransient
    private String proxyDomain;
    private Duration soTimeout;
    private String tlsVersions;
    private String urlCharset;
    private String userAgent;

    @ToString.Include(name = "keyStorePass")
    private String keyStorePassToString() {
        return "********";
    }
}
