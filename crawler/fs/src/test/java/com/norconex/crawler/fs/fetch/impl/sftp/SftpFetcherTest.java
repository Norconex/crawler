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
package com.norconex.crawler.fs.fetch.impl.sftp;

import static com.norconex.crawler.core.fetch.FetchDirective.DOCUMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.importer.doc.Doc;

@Timeout(30)
class SftpFetcherTest {

    private static class TestableSftpFetcher extends SftpFetcher {
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
    void testSftpFetcher() {
        var fetcher = new SftpFetcher();

        assertThatNoException()
                .isThrownBy(() -> BeanMapper.DEFAULT.assertWriteRead(fetcher));

        assertThat(fetcher.acceptRequest(
                new FileFetchRequest(new Doc("sftp://host/path"), DOCUMENT)))
                        .isTrue();
        assertThat(fetcher.acceptRequest(
                new FileFetchRequest(new Doc("ftp://host/path"), DOCUMENT)))
                        .isFalse();
    }

    @Test
    void testFetcherStartupAppliesOptionalOptions() {
        var fetcher = new SftpFetcher();
        var cfg = fetcher.getConfiguration();

        cfg.getCredentials().setUsername("alice");
        cfg.getCredentials().setPassword("secret");
        cfg.setConnectTimeout(Duration.ofSeconds(3));
        cfg.setKnownHosts(new File("known_hosts"));
        cfg.setFileNameEncoding("UTF-8");
        cfg.setPreferredAuthentications("publickey,password");
        cfg.setCompression("zlib");
        cfg.setStrictHostKeyChecking("yes");

        assertThatNoException().isThrownBy(() -> fetcher.fetcherStartup(null));
    }

    @Test
    void testResolvePathStripsLeadingSlashWhenUserDirIsRoot() throws Exception {
        var fetcher = new TestableSftpFetcher();
        fetcher.getConfiguration().setUserDirIsRoot(true);
        fetcher.fetcherStartup(null);

        var fs = mock(FileSystem.class);
        var path = mock(Path.class);
        fetcher.fileSystem = fs;
        when(fs.getPath("folder/file.txt")).thenReturn(path);

        var resolved = fetcher.resolvePath("sftp://host/folder/file.txt");

        assertThat(resolved).isSameAs(path);
        assertThat(fetcher.lastUri).hasToString("sftp://host/folder/file.txt");
        assertThat(fetcher.lastEnv).isNotNull();
    }

    @Test
    void testResolvePathKeepsLeadingSlashWhenUserDirIsRootDisabled()
            throws Exception {
        var fetcher = new TestableSftpFetcher();
        fetcher.getConfiguration().setUserDirIsRoot(false);
        fetcher.fetcherStartup(null);

        var fs = mock(FileSystem.class);
        var path = mock(Path.class);
        fetcher.fileSystem = fs;
        when(fs.getPath("/folder/file.txt")).thenReturn(path);

        var resolved = fetcher.resolvePath("sftp://host/folder/file.txt");

        assertThat(resolved).isSameAs(path);
        verify(fs).getPath("/folder/file.txt");
    }
}
