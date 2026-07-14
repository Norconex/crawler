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
package com.norconex.crawler.fs.fetch.impl.ftp;

import static com.norconex.crawler.core.fetch.FetchDirective.DOCUMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.github.robtimus.filesystems.ftp.DataChannelProtectionLevel;
import com.github.robtimus.filesystems.ftp.SecurityMode;
import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.net.Host;
import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.importer.doc.Doc;

@Timeout(30)
class FtpFetcherTest {

    private static class TestableFtpFetcher extends FtpFetcher {
        private FileSystem fileSystem;
        private URI lastUri;
        private Map<String, ?> lastEnv;

        @Override
        protected FileSystem getOrOpenFileSystem(
                URI referenceUri, Map<String, ?> env) {
            lastUri = referenceUri;
            lastEnv = env;
            return fileSystem;
        }
    }

    @BeforeEach
    void clearSystemProperties() {
        // Keep password decryption behavior deterministic across environments.
        System.clearProperty("norconex.encryption.password");
    }

    @Test
    void testFtpFetcher() {
        var fetcher = new FtpFetcher();
        var cfg = fetcher.getConfiguration();
        cfg.setShortMonthNames(List.of("jan", "feb"));
        cfg.getProxySettings().setHost(new Host("127.0.0.1", 3128));

        assertThat(cfg.getShortMonthNames()).containsExactly("jan", "feb");
        assertThatNoException()
                .isThrownBy(() -> BeanMapper.DEFAULT.assertWriteRead(fetcher));

        assertThat(fetcher.acceptRequest(
                new FileFetchRequest(new Doc("ftp://host/path"), DOCUMENT)))
                        .isTrue();
        assertThat(fetcher.acceptRequest(
                new FileFetchRequest(new Doc("ftps://host/path"), DOCUMENT)))
                        .isTrue();
        assertThat(fetcher.acceptRequest(
                new FileFetchRequest(new Doc("http://host/path"), DOCUMENT)))
                        .isFalse();
    }

    @Test
    void testFetcherStartupAppliesOptionalOptions() {
        var fetcher = new FtpFetcher();
        var cfg = fetcher.getConfiguration();

        cfg.getCredentials().setUsername("alice");
        cfg.getCredentials().setPassword("secret");
        cfg.setConnectTimeout(Duration.ofSeconds(5));
        cfg.setControlEncoding("UTF-8");
        cfg.setDataTimeout(Duration.ofSeconds(6));
        cfg.setPassiveMode(true);
        cfg.setRemoteVerificationDisabled(true);
        cfg.setSocketTimeout(Duration.ofSeconds(7));
        cfg.setControlKeepAliveTimeout(Duration.ofSeconds(8));
        cfg.setControlKeepAliveReplyTimeout(Duration.ofSeconds(9));
        cfg.setAutodetectUtf8(true);
        cfg.setDefaultDateFormat("yyyyMMddHHmmss");
        cfg.setRecentDateFormat("MMM d HH:mm");
        cfg.setServerLanguageCode("en");
        cfg.setShortMonthNames(List.of("jan", "feb"));
        cfg.setSecurityMode(SecurityMode.EXPLICIT);
        cfg.setDataChannelProtectionLevel(DataChannelProtectionLevel.PRIVATE);

        assertThatNoException().isThrownBy(() -> fetcher.fetcherStartup(null));
    }

    @Test
    void testResolvePathUsesFtpsEnvAndStripsLeadingSlashWhenUserDirIsRoot()
            throws Exception {
        var fetcher = new TestableFtpFetcher();
        fetcher.getConfiguration().setUserDirIsRoot(true);
        fetcher.fetcherStartup(null);

        var fs = mock(FileSystem.class);
        var path = mock(Path.class);
        fetcher.fileSystem = fs;
        org.mockito.Mockito.when(fs.getPath("rooted/file.txt"))
                .thenReturn(path);
        var resolved = fetcher.resolvePath("ftps://host/rooted/file.txt");

        assertThat(resolved).isSameAs(path);

        var ftpsField = FtpFetcher.class.getDeclaredField("ftpsEnv");
        ftpsField.setAccessible(true);
        assertThat(fetcher.lastEnv).isSameAs(ftpsField.get(fetcher));
        assertThat(fetcher.lastUri).hasToString("ftps://host/rooted/file.txt");
    }

    @Test
    void testResolvePathKeepsLeadingSlashWhenUserDirIsRootDisabled()
            throws Exception {
        var fetcher = new TestableFtpFetcher();
        fetcher.getConfiguration().setUserDirIsRoot(false);
        fetcher.fetcherStartup(null);

        var fs = mock(FileSystem.class);
        var path = mock(Path.class);
        fetcher.fileSystem = fs;
        org.mockito.Mockito.when(fs.getPath("/rooted/file.txt"))
                .thenReturn(path);

        var resolved = fetcher.resolvePath("ftp://host/rooted/file.txt");

        assertThat(resolved).isSameAs(path);
        org.mockito.Mockito.verify(fs).getPath("/rooted/file.txt");
    }
}
