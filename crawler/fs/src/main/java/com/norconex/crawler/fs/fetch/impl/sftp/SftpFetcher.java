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
package com.norconex.crawler.fs.fetch.impl.sftp;

import static com.norconex.crawler.fs.fetch.impl.FileFetchUtil.referenceStartsWith;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.github.robtimus.filesystems.sftp.SFTPEnvironment;
import com.norconex.commons.lang.encrypt.EncryptionUtil;
import com.norconex.crawler.core.fetch.FetchRequest;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.fs.fetch.impl.AbstractNioFetcher;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

/**
 * <p>
 * SFTP fetcher (<code>sftp://</code>), backed by the
 * <a href="https://github.com/robtimus/sftp-fs">sftp-fs</a> NIO.2
 * file system provider.
 * </p>
 * @see com.norconex.crawler.fs.fetch.impl.ftp.FtpFetcher
 */
@ToString
@EqualsAndHashCode
public class SftpFetcher extends AbstractNioFetcher<SftpFetcherConfig> {

    @Getter
    private final SftpFetcherConfig configuration = new SftpFetcherConfig();

    private Map<String, Object> env;

    @Override
    protected void fetcherStartup(CrawlerSession crawler) {
        super.fetcherStartup(crawler);
        var cfg = configuration;
        var sftpEnv = new SFTPEnvironment()
                .withConfig(
                        "StrictHostKeyChecking",
                        cfg.getStrictHostKeyChecking());
        if (cfg.getCredentials().isSet()) {
            sftpEnv.withUsername(cfg.getCredentials().getUsername())
                    .withPassword(EncryptionUtil.decryptPassword(
                            cfg.getCredentials()).toCharArray());
        }
        if (cfg.getConnectTimeout() != null) {
            sftpEnv.withConnectTimeout(
                    (int) cfg.getConnectTimeout().toMillis());
        }
        if (cfg.getKnownHosts() != null) {
            sftpEnv.withKnownHosts(cfg.getKnownHosts());
        }
        if (StringUtils.isNotBlank(cfg.getFileNameEncoding())) {
            sftpEnv.withFilenameEncoding(
                    Charset.forName(cfg.getFileNameEncoding()));
        }
        if (StringUtils.isNotBlank(cfg.getPreferredAuthentications())) {
            sftpEnv.withConfig(
                    "PreferredAuthentications",
                    cfg.getPreferredAuthentications());
        }
        if (StringUtils.isNotBlank(cfg.getCompression())) {
            sftpEnv.withConfig("compression.s2c", cfg.getCompression());
            sftpEnv.withConfig("compression.c2s", cfg.getCompression());
        }
        env = sftpEnv;
    }

    @Override
    protected boolean acceptRequest(@NonNull FetchRequest fetchRequest) {
        return referenceStartsWith(fetchRequest, "sftp://");
    }

    @Override
    protected Path resolvePath(String reference) throws IOException {
        var uri = URI.create(reference);
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
