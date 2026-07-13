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
package com.norconex.crawler.fs.fetch.impl.adlsgen2;

import static com.norconex.crawler.core.fetch.FetchDirective.DOCUMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.crawler.fs.FsTestUtil;
import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.importer.doc.Doc;

@Timeout(30)
class AdlsGen2FetcherTest {

    @Test
    void testAcceptRequest() {
        var f = new AdlsGen2Fetcher();
        assertThat(f.acceptRequest(new FileFetchRequest(
                new Doc("abfs://filesystem@account.dfs.core.windows.net/path"),
                DOCUMENT))).isTrue();
        assertThat(f.acceptRequest(new FileFetchRequest(
                new Doc("abfss://filesystem@account.dfs.core.windows.net/path"),
                DOCUMENT))).isTrue();
        assertThat(f.acceptRequest(new FileFetchRequest(
                new Doc("azblob://account/container/path"), DOCUMENT)))
                        .isFalse();
    }

    @Test
    void testWriteRead() {
        assertThatNoException().isThrownBy(
                () -> BeanMapper.DEFAULT.assertWriteRead(
                        FsTestUtil.randomize(AdlsGen2Fetcher.class)));
    }

    @Test
    void testBuildClientWithSharedKeyCredentials() {
        var f = new AdlsGen2Fetcher();
        f.getConfiguration().getCredentials()
                .setUsername("account")
                .setPassword("secret");
        assertThatNoException().isThrownBy(() -> {
            var client = f.buildClient(AdlsGen2Location.from(
                    java.net.URI.create(
                            "abfss://filesystem@account.dfs.core.windows.net/path")));
            assertThat(client).isNotNull();
        });
    }

    @Test
    void testBuildClientRejectsMismatchedAccount() {
        var f = new AdlsGen2Fetcher();
        f.getConfiguration().getCredentials()
                .setUsername("otheraccount")
                .setPassword("secret");
        assertThatThrownBy(() -> f.buildClient(AdlsGen2Location.from(
                java.net.URI.create(
                        "abfss://filesystem@account.dfs.core.windows.net/path"))))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("reference account");
    }

    @Test
    void testAclDisabledConfigToggle() {
        var f = new AdlsGen2Fetcher();
        assertThat(f.getConfiguration().isAclDisabled()).isFalse();
        f.getConfiguration().setAclDisabled(true);
        assertThat(f.getConfiguration().isAclDisabled()).isTrue();
    }
}
