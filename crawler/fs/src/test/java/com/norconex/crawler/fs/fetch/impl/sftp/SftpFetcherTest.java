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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import org.apache.commons.vfs2.FileSystemOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.crawler.core.CrawlerException;
import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.importer.doc.Doc;

@Timeout(30)
class SftpFetcherTest {

    @Test
    void testSftpFetcher() {
        var fetcher = new SftpFetcher();

        assertThatNoException()
                .isThrownBy(() -> BeanMapper.DEFAULT.assertWriteRead(fetcher));

        assertThat(fetcher.acceptFileRequest(
                new FileFetchRequest(new Doc("sftp://host/path"), DOCUMENT)))
                        .isTrue();
        assertThat(fetcher.acceptFileRequest(
                new FileFetchRequest(new Doc("ftp://host/path"), DOCUMENT)))
                        .isFalse();

        assertThatNoException().isThrownBy(
                () -> fetcher.applyFileSystemOptions(new FileSystemOptions()));

        fetcher.getConfiguration().setStrictHostKeyChecking("invalid");
        assertThatExceptionOfType(CrawlerException.class).isThrownBy(
                () -> fetcher.applyFileSystemOptions(new FileSystemOptions()));
    }
}
