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
package com.norconex.importer.handler.transformer.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.ByteArrayInputStream;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.map.Properties;
import com.norconex.importer.TestUtil;
import com.norconex.importer.handler.DocHandlerException;
import com.norconex.importer.handler.parser.ParseState;

@Timeout(30)
class BinaryContentTransformerTest {

    @Test
    void testWriteRead() {
        var t = new BinaryContentTransformer();
        t.getConfiguration().setFieldName("rawContent");
        BeanMapper.DEFAULT.assertWriteRead(t);
    }

    @Test
    void testEncodesPreParseContentAsBase64() throws Exception {
        var content = "test body".getBytes();
        var metadata = new Properties();

        var t = new BinaryContentTransformer();
        t.handle(
                TestUtil.newHandlerContext(
                        "n/a", new ByteArrayInputStream(content), metadata,
                        ParseState.PRE));

        assertThat(metadata.getString("binaryContent"))
                .isEqualTo(Base64.getEncoder().encodeToString(content));
    }

    @Test
    void testUsesConfiguredFieldName() throws Exception {
        var content = "test body".getBytes();
        var metadata = new Properties();

        var t = new BinaryContentTransformer();
        t.getConfiguration().setFieldName("customField");
        t.handle(
                TestUtil.newHandlerContext(
                        "n/a", new ByteArrayInputStream(content), metadata,
                        ParseState.PRE));

        assertThat(metadata.getString("customField"))
                .isEqualTo(Base64.getEncoder().encodeToString(content));
        assertThat(metadata.getString("binaryContent")).isNull();
    }

    @Test
    void testFailsWhenAlreadyParsed() {
        var t = new BinaryContentTransformer();
        assertThatExceptionOfType(DocHandlerException.class).isThrownBy(
                () -> t.handle(
                        TestUtil.newHandlerContext(
                                "n/a",
                                new ByteArrayInputStream("body".getBytes()),
                                new Properties(), ParseState.POST)));
    }
}
