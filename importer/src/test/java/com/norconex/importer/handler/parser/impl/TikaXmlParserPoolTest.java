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
package com.norconex.importer.handler.parser.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

import java.io.IOException;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.tika.exception.TikaException;
import org.apache.tika.utils.XMLReaderUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
class TikaXmlParserPoolTest {
    private Object previous;

    @BeforeEach
    void resetPolicy() throws Exception {
        previous = FieldUtils.readStaticField(TikaXmlParserPool.class,
                "initializedSize", true);
        FieldUtils.writeStaticField(TikaXmlParserPool.class,
                "initializedSize", null, true);
    }

    @AfterEach
    void restorePolicy() throws Exception {
        FieldUtils.writeStaticField(TikaXmlParserPool.class,
                "initializedSize", previous, true);
    }

    @Test
    void testOmittedDoesNotResize() throws Exception {
        try (var xml = mockStatic(XMLReaderUtils.class)) {
            xml.when(XMLReaderUtils::getPoolSize).thenReturn(10);
            TikaXmlParserPool.configure(null);
            TikaXmlParserPool.configure(null);
            xml.verify(() -> XMLReaderUtils.setPoolSize(10), times(0));
            assertThatThrownBy(() -> TikaXmlParserPool.configure(16))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Conflicting");
        }
    }

    @Test
    void testExplicitSixteenAppliedOnceAndConflictsFail() throws Exception {
        try (var xml = mockStatic(XMLReaderUtils.class)) {
            xml.when(XMLReaderUtils::getPoolSize).thenReturn(10, 16, 16, 16);
            TikaXmlParserPool.configure(16);
            TikaXmlParserPool.configure(16);
            TikaXmlParserPool.configure(null);
            xml.verify(() -> XMLReaderUtils.setPoolSize(16), times(1));
            assertThatThrownBy(() -> TikaXmlParserPool.configure(12))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Conflicting");
        }
    }

    @Test
    void testInvalidAndFailedInitialization() throws Exception {
        assertThatThrownBy(
                () -> new DefaultParserConfig().setXmlParserPoolSize(0))
                        .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                () -> new DefaultParserConfig().setXmlParserPoolSize(-1))
                        .isInstanceOf(IllegalArgumentException.class);
        assertThat(new DefaultParserConfig().getXmlParserPoolSize()).isNull();
        try (var xml = mockStatic(XMLReaderUtils.class)) {
            xml.when(XMLReaderUtils::getPoolSize).thenReturn(10);
            xml.when(() -> XMLReaderUtils.setPoolSize(16))
                    .thenThrow(new TikaException("test"));
            assertThatThrownBy(() -> TikaXmlParserPool.configure(16))
                    .isInstanceOf(IOException.class)
                    .hasCauseInstanceOf(TikaException.class);
            assertThat(FieldUtils.readStaticField(TikaXmlParserPool.class,
                    "initializedSize", true)).isNull();
        }
    }
}
