/* Copyright 2010-2026 Norconex Inc.
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
package com.norconex.committer.solr;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.committer.core.batch.queue.impl.FsQueue;
import com.norconex.commons.lang.ResourceLoader;
import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.bean.BeanMapper.Format;
import com.norconex.commons.lang.map.PropertyMatcher;
import com.norconex.commons.lang.security.Credentials;
import com.norconex.commons.lang.text.TextMatcher;

/**
 * SolrCommitter configuration tests.

 */
@Timeout(30)
class SolrCommitterConfigTest {

    @Test
    void testWriteRead() {
        var c = new SolrCommitter();

        var q = new FsQueue();
        q.getConfiguration().setBatchSize(10);
        q.getConfiguration().setMaxPerFolder(5);
        c.getConfiguration().setQueue(q);

        var creds = new Credentials();
        creds.setPassword("mypassword");
        creds.setUsername("myusername");
        c.getConfiguration().setCredentials(creds);

        c.getConfiguration().setFieldMapping("subject", "title");
        c.getConfiguration().setFieldMapping("body", "content");

        c.getConfiguration().getRestrictions().add(
                new PropertyMatcher(
                        TextMatcher.basic("document.reference"),
                        TextMatcher.wildcard("*.pdf")));
        c.getConfiguration().getRestrictions().add(
                new PropertyMatcher(
                        TextMatcher.basic("title"),
                        TextMatcher.wildcard("Nah!")));

        c.getConfiguration().setSourceIdField("sourceId");
        c.getConfiguration().setTargetIdField("targetId");
        c.getConfiguration().setTargetContentField("targetContent");

        c.getConfiguration().setSolrClientType(
                SolrClientType.CONCURRENT_UPDATE_HTTP2);

        c.getConfiguration().setSolrCommitDisabled(true);

        c.getConfiguration().setSolrURL("http://solrurl.com/test");

        c.getConfiguration().setUpdateUrlParam("param1", "value1a");
        c.getConfiguration().setUpdateUrlParam("param1", "value1b");
        c.getConfiguration().setUpdateUrlParam("param2", "value2");

        BeanMapper.DEFAULT.assertWriteRead(c);
    }

    @Test
    void testValidation() {
        Assertions.assertDoesNotThrow(() -> {
            try (var r = ResourceLoader.getXmlReader(this.getClass())) {
                var committer = BeanMapper.DEFAULT.read(
                        SolrCommitter.class, r, Format.XML);
                assertThat(committer
                        .getConfiguration()
                        .getUpdateUrlParam("param1")).contains("value1");
                assertThat(committer
                        .getConfiguration()
                        .getUpdateUrlParamNames()).contains("param1", "param2");
            }
        });
    }
}
