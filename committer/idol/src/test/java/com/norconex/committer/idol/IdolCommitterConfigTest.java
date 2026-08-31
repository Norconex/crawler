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
package com.norconex.committer.idol;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.committer.core.batch.queue.impl.FsQueue;
import com.norconex.commons.lang.ResourceLoader;
import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.bean.BeanMapper.Format;
import com.norconex.commons.lang.map.PropertyMatcher;
import com.norconex.commons.lang.text.TextMatcher;

@Timeout(30)
class IdolCommitterConfigTest {

    @Test
    void testWriteRead() {
        var c = new IdolCommitter();

        var q = new FsQueue();
        q.getConfiguration().setBatchSize(10);
        q.getConfiguration().setMaxPerFolder(5);
        c.getConfiguration()
                .setQueue(q)
                .setFieldMapping("subject", "title")
                .setFieldMapping("body", "content");

        c.getConfiguration().getRestrictions().add(
                new PropertyMatcher(
                        TextMatcher.basic("document.reference"),
                        TextMatcher.wildcard("*.pdf")));
        c.getConfiguration().getRestrictions().add(
                new PropertyMatcher(
                        TextMatcher.basic("title"),
                        TextMatcher.wildcard("Nah!")));

        var cfg = c.getConfiguration();
        cfg.setUrl("http://somehost:9001");
        cfg.setCfs(true);
        cfg.setDatabaseName("mydatabase");
        cfg.setSourceContentField("sourceContentField");
        cfg.setSourceReferenceField("sourceReferenceField");
        cfg.getDreAddDataParams().put("aparam1", "avalue1");
        cfg.getDreAddDataParams().put("aparam2", "avalue2");
        cfg.getDreDeleteRefParams().put("dparam1", "dvalue1");
        cfg.getDreDeleteRefParams().put("dparam2", "dvalue2");

        BeanMapper.DEFAULT.assertWriteRead(c);
    }

    @Test
    void testValidation() {
        Assertions.assertDoesNotThrow(() -> {
            try (var r = ResourceLoader.getXmlReader(this.getClass())) {
                BeanMapper.DEFAULT.read(IdolCommitter.class, r, Format.XML);
            }
        });
    }
}
