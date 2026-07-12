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

import org.apache.commons.vfs2.FileSystemOptions;
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
    }
}
