/* Copyright 2024-2026 Norconex Inc.
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
package com.norconex.crawler.fs.spi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.crawler.fs.doc.operations.checksum.FsMetadataChecksummer;
import com.norconex.crawler.fs.fetch.impl.azureblob.AzureBlobFetcher;
import com.norconex.crawler.fs.fetch.impl.cmis.CmisFetcher;
import com.norconex.crawler.fs.fetch.impl.ftp.FtpFetcher;
import com.norconex.crawler.fs.fetch.impl.gcs.GcsFetcher;
import com.norconex.crawler.fs.fetch.impl.hdfs.HdfsFetcher;
import com.norconex.crawler.fs.fetch.impl.local.LocalFetcher;
import com.norconex.crawler.fs.fetch.impl.s3.S3Fetcher;
import com.norconex.crawler.fs.fetch.impl.sftp.SftpFetcher;
import com.norconex.crawler.fs.fetch.impl.smb.SmbFetcher;
import com.norconex.crawler.fs.fetch.impl.webdav.WebDavFetcher;

@Timeout(30)
class CrawlerFsPtProviderTest {

    @Test
    void test() {
        assertThat(BeanMapper.DEFAULT.getPolymorphicTypes().values())
                .contains(
                        FsMetadataChecksummer.class,
                        AzureBlobFetcher.class,
                        CmisFetcher.class,
                        FtpFetcher.class,
                        GcsFetcher.class,
                        HdfsFetcher.class,
                        LocalFetcher.class,
                        S3Fetcher.class,
                        SftpFetcher.class,
                        SmbFetcher.class,
                        WebDavFetcher.class);
    }
}
