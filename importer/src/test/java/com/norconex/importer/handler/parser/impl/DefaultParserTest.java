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
package com.norconex.importer.handler.parser.impl;

import static org.assertj.core.api.Assertions.assertThatNoException;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.config.Configurable;
import com.norconex.commons.lang.text.TextMatcher;

@Timeout(30)
class DefaultParserTest {

    @Test
    void testWriteRead() {

        var parser = Configurable.configure(
                new DefaultParser(),
                cfg -> cfg.getEmbeddedConfig()
                        .setSplitContentTypes(
                                List.of(TextMatcher.regex(".*splitBlah.*")))
                        .setSkipEmbeddedContentTypes(
                                List.of(TextMatcher.regex(".*embedBlah.*")))
                        .setSkipEmbeddedOfContentTypes(
                                List.of(TextMatcher.regex(".*embedOfBlah.*"))),
                cfg -> cfg.getOcrConfig()
                        .setContentTypeMatcher(TextMatcher.regex(".*blah.*"))
                        .setLanguage("eng+fra")
                        .setTesseractPath(Path.of("/tmp/blah")),
                cfg -> cfg.setPoolSize(2).setXmlParserPoolSize(16),
                cfg -> cfg.setErrorsSaveDir(Path.of("/tmp/saveDir")));

        assertThatNoException()
                .isThrownBy(() -> BeanMapper.DEFAULT.assertWriteRead(parser));
    }
}
