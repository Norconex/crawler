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
package com.norconex.crawler.fs.fetch.impl.ftp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.github.robtimus.filesystems.ftp.DataChannelProtectionLevel;
import com.github.robtimus.filesystems.ftp.SecurityMode;
import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.commons.lang.net.ProxySettings;
import com.norconex.crawler.fs.fetch.impl.BaseAuthNioFetcherConfig;

import jakarta.xml.bind.annotation.XmlTransient;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * Configuration for {@link FtpFetcher}.
 * </p>
 */
@Data
@Accessors(chain = true)
public class FtpFetcherConfig extends BaseAuthNioFetcherConfig {

    private boolean autodetectUtf8;
    private Duration connectTimeout;
    private String controlEncoding;
    private Duration dataTimeout;
    private String defaultDateFormat;
    private boolean passiveMode;
    @XmlTransient
    private final ProxySettings proxySettings = new ProxySettings();
    private String recentDateFormat;
    private boolean remoteVerificationDisabled;
    private String serverLanguageCode;
    @XmlTransient
    private final List<String> shortMonthNames = new ArrayList<>();
    private Duration socketTimeout;
    private Duration controlKeepAliveTimeout;
    private Duration controlKeepAliveReplyTimeout;
    private boolean userDirIsRoot;

    // Only apply to FTPS
    private SecurityMode securityMode;
    private DataChannelProtectionLevel dataChannelProtectionLevel;

    public List<String> getShortMonthNames() {
        return Collections.unmodifiableList(shortMonthNames);
    }

    public final FtpFetcherConfig setShortMonthNames(
            List<String> shortMonthNames) {
        CollectionUtil.setAll(this.shortMonthNames, shortMonthNames);
        return this;
    }
}
