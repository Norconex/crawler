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
package com.norconex.crawler.fs.fetch.impl;

import static com.norconex.crawler.fs.FsTestUtil.getUpsertRequestContent;
import static com.norconex.crawler.fs.FsTestUtil.getUpsertRequestMeta;
import static com.norconex.crawler.fs.doc.FsDocMetadata.FILE_SIZE;
import static com.norconex.importer.doc.DocMetaConstants.CONTENT_ENCODING;
import static com.norconex.importer.doc.DocMetaConstants.CONTENT_TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.committer.core.UpsertRequest;
import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.file.ContentType;
import com.norconex.crawler.core.fetch.Fetcher;
import com.norconex.crawler.fs.FsTestUtil;

@Timeout(60)
@SuppressWarnings("null")
public abstract class AbstractFileFetcherTest {

    @TempDir
    private Path tempDir;

    protected abstract Fetcher fetcher();

    @Test
    void testFetchFiles() throws Exception {
        var fetcher = fetcher();
        var basePath = getStartPath();

        var mem = FsTestUtil
                .crawlWithFetcher(tempDir, fetcher, basePath);

        assertThat(mem.getUpsertCount()).isEqualTo(8);
        assertThat(mem.getUpsertRequests())
                .map(UpsertRequest::getReference)
                .containsExactlyInAnyOrder(
                        basePath + "/bye.txt",
                        basePath + "/embedded.zip",
                        basePath + "/hello.txt",
                        basePath + "/UTF-8.txt",
                        basePath + "/windows-1252.txt",
                        basePath + "/imgs/160x120.png",
                        basePath + "/imgs/320x240.png",
                        basePath + "/pdfs/plain.pdf");

        // Assert content
        assertThat(getUpsertRequestContent(
                mem, basePath + "/bye.txt"))
                        .contains("Bye World!");
        assertThat(getUpsertRequestContent(
                mem, basePath + "/pdfs/plain.pdf"))
                        .contains("Hey Norconex, this is a test.");

        // Assert char encoding
        assertThat(getUpsertRequestMeta(
                mem, basePath + "/UTF-8.txt", CONTENT_ENCODING))
                        .isEqualTo("UTF-8");
        assertThat(getUpsertRequestMeta(
                mem, basePath + "/windows-1252.txt",
                CONTENT_ENCODING))
                        .isEqualTo("windows-1252");

        // Assert content type
        assertThat(getUpsertRequestMeta(
                mem, basePath + "/UTF-8.txt", CONTENT_TYPE))
                        .startsWith(ContentType.TEXT
                                .toString());
        assertThat(getUpsertRequestMeta(
                mem, basePath + "/pdfs/plain.pdf",
                CONTENT_TYPE))
                        .isEqualTo(ContentType.PDF
                                .toString());

        // Assert file size
        assertThat(getUpsertRequestMeta(
                mem, basePath + "/imgs/320x240.png", FILE_SIZE))
                        .isEqualTo("1853");
        assertThat(getUpsertRequestMeta(
                mem, basePath + "/pdfs/plain.pdf", FILE_SIZE))
                        .isEqualTo("15987");

        // Assert last modified (UTC)
        //TODO reliably test dates. Probably best to create new files

        assertThatNoException()
                .isThrownBy(
                        () -> BeanMapper.DEFAULT
                                .assertWriteRead(
                                        FsTestUtil.randomize(
                                                fetcher.getClass())));
        // Assert ACLs

        //TODO extract ACL, possibly making it a flag if costly?
    }

    protected abstract String getStartPath();
}
