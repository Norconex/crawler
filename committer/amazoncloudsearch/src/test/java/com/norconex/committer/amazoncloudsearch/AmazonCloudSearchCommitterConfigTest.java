/* Copyright 2017-2026 Norconex Inc.
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
package com.norconex.committer.amazoncloudsearch;

import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.committer.core.batch.queue.impl.FsQueue;
import com.norconex.commons.lang.ResourceLoader;
import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.bean.BeanMapper.Format;
import com.norconex.commons.lang.map.PropertyMatcher;
import com.norconex.commons.lang.net.Host;
import com.norconex.commons.lang.security.Credentials;
import com.norconex.commons.lang.text.TextMatcher;

@Timeout(30)
class AmazonCloudSearchCommitterConfigTest {

    @Test
    void testWriteRead() throws IOException {
        var q = new FsQueue();
        q.getConfiguration()
                .setBatchSize(10)
                .setMaxPerFolder(5);

        var creds = new Credentials();
        creds.setPassword("mypassword");
        creds.setUsername("myusername");

        var c = new AmazonCloudSearchCommitter();
        c.getConfiguration()
                .setAccessKey("accessKey")
                .setSecretKey("secretKey")
                .setServiceEndpoint("serviceEndpoint")
                .setSigningRegion("signingRegion")
                .setFixBadIds(true)
                .setSourceIdField("mySourceIdField")
                .setTargetContentField("myTargetContentField")
                .setQueue(q)
                .setFieldMapping("subject", "title")
                .setFieldMapping("body", "content")
                .addRestriction(
                        new PropertyMatcher(
                                TextMatcher.basic("document.reference"),
                                TextMatcher.wildcard("*.pdf")))
                .addRestriction(
                        new PropertyMatcher(
                                TextMatcher.basic("title"),
                                TextMatcher.wildcard("Nah!")));

        c.getConfiguration().getProxySettings().setHost(
                new Host("example.com", 1234));

        assertThatNoException()
                .isThrownBy(() -> BeanMapper.DEFAULT.assertWriteRead(c));
    }

    @Test
    void testValidation() {
        assertThatNoException().isThrownBy(() -> {
            try (var r = ResourceLoader.getXmlReader(this.getClass())) {
                BeanMapper.DEFAULT.read(
                        AmazonCloudSearchCommitter.class, r, Format.XML);
            }
        });
    }
}
