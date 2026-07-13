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

import static com.norconex.crawler.fs.fetch.impl.FileFetchUtil.referenceStartsWith;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.net.ftp.FTPClientConfig;

import com.github.robtimus.filesystems.ftp.ConnectionMode;
import com.github.robtimus.filesystems.ftp.FTPEnvironment;
import com.github.robtimus.filesystems.ftp.FTPSEnvironment;
import com.norconex.commons.lang.encrypt.EncryptionUtil;
import com.norconex.crawler.core.fetch.FetchRequest;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.fs.fetch.impl.AbstractNioFetcher;
import com.norconex.crawler.fs.fetch.impl.sftp.SftpFetcher;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

/**
 * <p>
 * FTP (<code>ftp://</code>) and FTPS (<code>ftps://</code>) fetcher, backed
 * by the <a href="https://github.com/robtimus/ftp-fs">ftp-fs</a> NIO.2
 * file system provider.
 * </p>
 *
 * @see SftpFetcher
 */
@ToString
@EqualsAndHashCode
public class FtpFetcher extends AbstractNioFetcher<FtpFetcherConfig> {

    @Getter
    private final FtpFetcherConfig configuration = new FtpFetcherConfig();

    private Map<String, Object> ftpEnv;
    private Map<String, Object> ftpsEnv;

    @Override
    protected void fetcherStartup(CrawlerSession crawler) {
        super.fetcherStartup(crawler);

        var ftp = new FTPEnvironment();
        applyCommonOptions(ftp);
        ftpEnv = ftp;

        var ftps = new FTPSEnvironment();
        applyCommonOptions(ftps);
        if (configuration.getSecurityMode() != null) {
            ftps.withSecurityMode(configuration.getSecurityMode());
        }
        if (configuration.getDataChannelProtectionLevel() != null) {
            ftps.withDataChannelProtectionLevel(
                    configuration.getDataChannelProtectionLevel());
        }
        ftpsEnv = ftps;
    }

    private void applyCommonOptions(FTPEnvironment env) {
        var cfg = configuration;
        if (cfg.getCredentials().isSet()) {
            env.withCredentials(
                    cfg.getCredentials().getUsername(),
                    EncryptionUtil.decryptPassword(
                            cfg.getCredentials()).toCharArray());
        }
        if (cfg.getConnectTimeout() != null) {
            env.withConnectTimeout((int) cfg.getConnectTimeout().toMillis());
        }
        if (StringUtils.isNotBlank(cfg.getControlEncoding())) {
            env.withControlEncoding(cfg.getControlEncoding());
        }
        if (cfg.getDataTimeout() != null) {
            env.withDataTimeout(cfg.getDataTimeout());
        }
        env.withConnectionMode(
                cfg.isPassiveMode()
                        ? ConnectionMode.PASSIVE
                        : ConnectionMode.ACTIVE);
        env.withRemoteVerificationEnabled(!cfg.isRemoteVerificationDisabled());
        if (cfg.getSocketTimeout() != null) {
            env.withSoTimeout((int) cfg.getSocketTimeout().toMillis());
        }
        if (cfg.getControlKeepAliveTimeout() != null) {
            env.withControlKeepAliveTimeout(cfg.getControlKeepAliveTimeout());
        }
        if (cfg.getControlKeepAliveReplyTimeout() != null) {
            env.withControlKeepAliveReplyTimeout(
                    cfg.getControlKeepAliveReplyTimeout());
        }
        env.withAutodetectEncoding(cfg.isAutodetectUtf8());
        env.withClientConfig(buildClientConfig(cfg));
    }

    private FTPClientConfig buildClientConfig(FtpFetcherConfig cfg) {
        var clientConfig = new FTPClientConfig();
        if (StringUtils.isNotBlank(cfg.getDefaultDateFormat())) {
            clientConfig.setDefaultDateFormatStr(cfg.getDefaultDateFormat());
        }
        if (StringUtils.isNotBlank(cfg.getRecentDateFormat())) {
            clientConfig.setRecentDateFormatStr(cfg.getRecentDateFormat());
        }
        if (StringUtils.isNotBlank(cfg.getServerLanguageCode())) {
            clientConfig.setServerLanguageCode(cfg.getServerLanguageCode());
        }
        if (!cfg.getShortMonthNames().isEmpty()) {
            clientConfig.setShortMonthNames(
                    String.join("|", cfg.getShortMonthNames()));
        }
        return clientConfig;
    }

    @Override
    protected boolean acceptRequest(@NonNull FetchRequest fetchRequest) {
        return referenceStartsWith(fetchRequest, "ftp://", "ftps://");
    }

    @Override
    protected Path resolvePath(String reference) throws IOException {
        var uri = URI.create(reference);
        var env = "ftps".equalsIgnoreCase(uri.getScheme()) ? ftpsEnv : ftpEnv;
        var fs = getOrOpenFileSystem(uri, env);
        var path = uri.getPath();
        if (configuration.isUserDirIsRoot()
                && path != null
                && path.startsWith("/")) {
            // Reinterpret as relative to the connection's default
            // (home) directory rather than the true file system root.
            path = path.substring(1);
        }
        return fs.getPath(path);
    }
}
