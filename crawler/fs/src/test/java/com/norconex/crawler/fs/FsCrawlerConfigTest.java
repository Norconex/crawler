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
package com.norconex.crawler.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.StringReader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.bean.BeanMapper.Format;
import com.norconex.crawler.fs.doc.operations.checksum.FsMetadataChecksummer;

@Timeout(30)
class FsCrawlerConfigTest {

    @Test
    void testMetadataChecksummerIsThereByDefault() {
        // So that a file that did not change is not read and imported again,
        // like a page on the web has its own default.
        assertThat(new FsCrawlerConfig().getMetadataChecksummer())
                .isInstanceOf(FsMetadataChecksummer.class);
    }

    @Test
    void testConfigurationWithoutOneStillGetsTheDefault() {
        var config = BeanMapper.DEFAULT.read(
                FsCrawlerConfig.class,
                new StringReader("{\"id\":\"test\"}"),
                Format.JSON);

        assertThat(config.getMetadataChecksummer())
                .isInstanceOf(FsMetadataChecksummer.class);
    }

    @Test
    void testMetadataChecksummerCanBeTurnedOff() {
        var config = BeanMapper.DEFAULT.read(
                FsCrawlerConfig.class,
                new StringReader(
                        "{\"id\":\"test\",\"metadataChecksummer\":null}"),
                Format.JSON);

        assertThat(config.getMetadataChecksummer()).isNull();
        assertThat(new FsCrawlerConfig().setMetadataChecksummer(null)
                .getMetadataChecksummer()).isNull();
    }

    @Test
    void testWriteRead() {
        assertThatNoException().isThrownBy(
                () -> BeanMapper.DEFAULT.assertWriteRead(
                        new FsCrawlerConfig().setId("test")));
    }
}
