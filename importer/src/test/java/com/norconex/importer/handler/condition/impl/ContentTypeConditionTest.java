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
package com.norconex.importer.handler.condition.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.file.ContentType;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.TestUtil;

class ContentTypeConditionTest {

    private static boolean test(ContentTypeCondition condition, String type)
            throws IOException {
        return condition.test(TestUtil.newHandlerContext("ref",
                new ByteArrayInputStream(new byte[0]),
                type == null ? null : ContentType.valueOf(type)));
    }

    @Test
    void matchesTheContentTypeWithoutItsCharset() throws IOException {
        var condition =
                new ContentTypeCondition(TextMatcher.basic("text/html"));

        assertThat(test(condition, "text/html")).isTrue();
        assertThat(test(condition, "text/html; charset=UTF-8")).isTrue();
        assertThat(test(condition, "application/pdf")).isFalse();
    }

    @Test
    void aDocumentWithNoKnownContentTypeMeetsIt() throws IOException {
        var condition =
                new ContentTypeCondition(TextMatcher.basic("text/html"));

        assertThat(test(condition, null)).isTrue();
    }

    @Test
    void canBeWrittenAndRead() {
        var condition = new ContentTypeCondition(
                TextMatcher.regex("text/.*").ignoreCase());

        BeanMapper.DEFAULT.assertWriteRead(condition);
    }
}
