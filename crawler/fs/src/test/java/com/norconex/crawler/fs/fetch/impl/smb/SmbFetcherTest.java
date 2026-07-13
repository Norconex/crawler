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
package com.norconex.crawler.fs.fetch.impl.smb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import com.norconex.crawler.core.session.CrawlerSession;

class SmbFetcherTest {

    @Test
    void testAcceptRequest() {
        var fetcher = new SmbFetcher();

        assertThat(fetcher
                .accept(new com.norconex.crawler.fs.fetch.FileFetchRequest(
                        new com.norconex.importer.doc.Doc(
                                "smb://fileserver.example.com/share/file.txt"),
                        com.norconex.crawler.core.fetch.FetchDirective.METADATA)))
                                .isTrue();
        assertThat(fetcher
                .accept(new com.norconex.crawler.fs.fetch.FileFetchRequest(
                        new com.norconex.importer.doc.Doc(
                                "abfss://acct.dfs.core.windows.net/share/file.txt"),
                        com.norconex.crawler.core.fetch.FetchDirective.METADATA)))
                                .isFalse();
    }

    @Test
    void testStartupResolvePathAndShutdownWithoutCredentials()
            throws Exception {
        var fetcher = new SmbFetcher();
        var session = mock(CrawlerSession.class);

        fetcher.fetcherStartup(session);
        var filePath = fetcher.resolvePath(
                "smb://fileserver.example.com/share/doc.txt");
        var rootPath = fetcher.resolvePath("smb://fileserver.example.com");

        assertThat(filePath.toString()).isEqualTo("/share/doc.txt");
        assertThat(rootPath.toString()).isEqualTo("/");
        assertThat(provider(fetcher).openFileSystems()).hasSize(1);

        fetcher.fetcherShutdown(session);
        assertThat(provider(fetcher).openFileSystems()).isEmpty();
    }

    @Test
    void testStartupResolvePathWithCredentials() throws Exception {
        var fetcher = new SmbFetcher();
        fetcher.getConfiguration().setDomain("WORKGROUP");
        fetcher.getConfiguration().getCredentials().setUsername("joe");
        fetcher.getConfiguration().getCredentials().setPassword("joepwd");
        var session = mock(CrawlerSession.class);

        fetcher.fetcherStartup(session);
        var path = fetcher.resolvePath(
                "smb://fileserver.example.com/share/secret.txt");

        assertThat(path.toString()).isEqualTo("/share/secret.txt");
        fetcher.fetcherShutdown(session);
    }

    private static SmbFileSystemProvider provider(SmbFetcher fetcher)
            throws Exception {
        var field = SmbFetcher.class.getDeclaredField("provider");
        field.setAccessible(true);
        return (SmbFileSystemProvider) field.get(fetcher);
    }
}
