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

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.crawler.core.fetch.FetchRequest;
import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.crawler.fs.fetch.FolderPathsFetchRequest;
import com.norconex.importer.doc.Doc;

import lombok.Getter;
import lombok.NonNull;

@Timeout(30)
class AbstractAuthVfsFetcherTest {

    @Test
    void testAcceptRequest() {
        var fetcher = new MockNioFetcher();
        assertThat(fetcher.acceptRequest(
                new FileFetchRequest(new Doc("mock://host/path"), DOCUMENT)))
                        .isTrue();
        assertThat(fetcher.acceptRequest(
                new FolderPathsFetchRequest(new Doc("mock://host/path"))))
                        .isTrue();
        assertThat(fetcher.acceptRequest(
                new FileFetchRequest(new Doc("other://host/path"), DOCUMENT)))
                        .isFalse();
    }

    @Test
    void testBaseAuthNioFetcherConfig() {
        var cfg = new MockNioFetcherConfig();

        assertThat(cfg.getDomain()).isNull();
        cfg.setDomain("testDomain");
        assertThat(cfg.getDomain()).isEqualTo("testDomain");

        assertThat(cfg.getCredentials()).isNotNull();
        cfg.getCredentials().setUsername("user").setPassword("password");
        assertThat(cfg.getCredentials().getUsername()).isEqualTo("user");
    }

    @Getter
    private static class MockNioFetcher
            extends AbstractNioFetcher<MockNioFetcherConfig> {
        private final MockNioFetcherConfig configuration =
                new MockNioFetcherConfig();

        @Override
        protected boolean acceptRequest(@NonNull FetchRequest fetchRequest) {
            return fetchRequest.getDoc().getReference().startsWith("mock://");
        }

        @Override
        protected Path resolvePath(String reference) throws IOException {
            return Paths.get("/");
        }
    }

    private static class MockNioFetcherConfig extends BaseAuthNioFetcherConfig {
    }
}
