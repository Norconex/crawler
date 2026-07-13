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
package com.norconex.crawler.fs.fetch.impl.webdav;

import static com.norconex.crawler.core.fetch.FetchDirective.DOCUMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.net.Host;
import com.norconex.commons.lang.security.Credentials;
import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.importer.doc.Doc;

@Timeout(30)
class WebDavFetcherTest {

    @Test
    void testWebDavFetcher() {
        var fetcher = new WebDavFetcher();
        var cfg = fetcher.getConfiguration();
        cfg.setKeyStorePass("abc123");
        cfg.getProxySettings()
                .setCredentials(new Credentials("one", "two"))
                .setHost(new Host("127.0.0.1", 8080));

        assertThat(cfg.toString()).contains("keyStorePass=********");
        assertThatNoException()
                .isThrownBy(() -> BeanMapper.DEFAULT.assertWriteRead(fetcher));

        assertThat(fetcher.acceptFileRequest(new FileFetchRequest(
                new Doc("webdav://host/path"), DOCUMENT))).isTrue();
        assertThat(fetcher.acceptFileRequest(new FileFetchRequest(
                new Doc("https://host/path"), DOCUMENT))).isTrue();
        assertThat(fetcher.acceptFileRequest(new FileFetchRequest(
                new Doc("ftp://host/path"), DOCUMENT))).isFalse();

        assertThatNoException().isThrownBy(
                () -> fetcher.applyFileSystemOptions(new FileSystemOptions()));
    void testAcceptRequest() {
        var fetcher = new WebDavFetcher();
        assertThat(fetcher.acceptRequest(new FileFetchRequest(
                new Doc("webdav://host/repo/file.txt"), DOCUMENT)))
                        .isTrue();
        assertThat(fetcher.acceptRequest(new FileFetchRequest(
                new Doc("webdavs://host/repo/file.txt"), DOCUMENT)))
                        .isTrue();
        assertThat(fetcher.acceptRequest(new FileFetchRequest(
                new Doc("https://host/repo/file.txt"), DOCUMENT)))
                        .isTrue();
        assertThat(fetcher.acceptRequest(new FileFetchRequest(
                new Doc("ftp://host/repo/file.txt"), DOCUMENT)))
                        .isFalse();
    }

    @Test
    void testWriteRead() {
        assertThatNoException().isThrownBy(
                () -> BeanMapper.DEFAULT.assertWriteRead(new WebDavFetcher()));
    }

    @Test
    void testResolvePathForRootAndChild() throws Exception {
        var fetcher = new WebDavFetcher();

        Path root = fetcher.resolvePath("webdav://example.com");
        Path child = fetcher.resolvePath("webdav://example.com/a/b/c.txt");

        assertThat(root.toString()).isEqualTo("/");
        assertThat(child.toString()).isEqualTo("/a/b/c.txt");

        assertThatNoException().isThrownBy(() -> fetcher.fetcherShutdown(null));
    }

    @Test
    void testBuildHttpClientWithProxyAndTimeouts() {
        var fetcher = new WebDavFetcher();
        fetcher.getConfiguration()
                .setUserAgent("nx-test-agent")
                .setConnectionTimeout(Duration.ofSeconds(3))
                .setSoTimeout(Duration.ofSeconds(4))
                .setFollowRedirect(true)
                .setKeepAlive(false)
                .setHostnameVerificationEnabled(false)
                .setTlsVersions("TLSv1.3,TLSv1.2");
        fetcher.getConfiguration().getProxySettings()
                .setHost(new Host("localhost", 8888));

        assertThatNoException().isThrownBy(() -> {
            try (var client = fetcher.buildHttpClient()) {
                assertThat(client).isNotNull();
            }
        });
    }

    @Test
    void testBuildHttpClientFailsWithInvalidKeyStore() {
        var fetcher = new WebDavFetcher();
        fetcher.getConfiguration()
                .setKeyStoreFile("does-not-exist.jks")
                .setKeyStoreType("JKS")
                .setKeyStorePass("secret");

        assertThatThrownBy(fetcher::buildHttpClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "Could not configure WebDAV TLS settings");
    }
}
