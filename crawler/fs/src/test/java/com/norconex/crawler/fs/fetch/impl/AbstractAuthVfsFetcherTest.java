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
package com.norconex.crawler.fs.fetch.impl;

import static com.norconex.crawler.core.fetch.FetchDirective.DOCUMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import org.apache.commons.vfs2.FileSystemOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.crawler.fs.fetch.FolderPathsFetchRequest;
import com.norconex.importer.doc.Doc;

import lombok.Getter;
import lombok.Setter;

@Timeout(30)
class AbstractAuthVfsFetcherTest {

    @Test
    void testAcceptRequest() {
        var fetcher = new MockAuthFetcher();
        fetcher.setAcceptFileRequests(true);
        assertThat(fetcher.acceptRequest(
                new FileFetchRequest(new Doc("sftp://host/path"), DOCUMENT)))
                        .isTrue();

        fetcher.setAcceptFileRequests(false);
        assertThat(fetcher.acceptRequest(
                new FileFetchRequest(new Doc("sftp://host/path"), DOCUMENT)))
                        .isFalse();

        assertThat(fetcher.acceptRequest(
                new FolderPathsFetchRequest(new Doc("sftp://host/path"))))
                        .isTrue();
    }

    @Test
    void testApplyAuthenticationOptions() {
        var fetcher = new MockAuthFetcher();
        var opts = new FileSystemOptions();

        assertThatNoException()
                .isThrownBy(() -> fetcher.applyAuthenticationOptions(opts));

        fetcher.getConfiguration().setDomain("domain");
        fetcher.getConfiguration().getCredentials()
                .setUsername("user")
                .setPassword("password");

        assertThatNoException()
                .isThrownBy(() -> fetcher.applyAuthenticationOptions(opts));
    }

    @Getter
    @Setter
    private static class MockAuthFetcher
            extends AbstractAuthVfsFetcher<MockAuthFetcherConfig> {
        private final MockAuthFetcherConfig configuration =
                new MockAuthFetcherConfig();
        private boolean acceptFileRequests;

        @Override
        protected boolean acceptFileRequest(FileFetchRequest fetchRequest) {
            return acceptFileRequests;
        }

        @Override
        protected void applyFileSystemOptions(FileSystemOptions opts) {
            // NOOP
        }
    }

    private static class MockAuthFetcherConfig extends BaseAuthVfsFetcherConfig {
    }
}
