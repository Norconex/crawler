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
package com.norconex.importer.handler.parser.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.TestUtil;

@Timeout(30)
class TikaParserTest {

    @Test
    void testWriteRead() {
        var parser = Configurable.configure(
                new TikaParser(),
                cfg -> cfg.setPoolSize(2),
                cfg -> cfg.setTikaConfigFile(null));
        assertThatNoException()
                .isThrownBy(() -> BeanMapper.DEFAULT.assertWriteRead(parser));
    }

    @Test
    void testParse(@TempDir Path tempDir) throws IOException {

        var tikaConfig = tempDir.resolve("tika.xml");
        Files.writeString(tikaConfig, "<properties></properties>");

        var parser = Configurable.configure(
                new TikaParser(),
                cfg -> cfg.setTikaConfigFile(tikaConfig.toString()));
        parser.init();

        var ctx = TestUtil.newHandlerContext("index.html",
                "<html><body>content</body></html>");
        parser.handle(ctx);

        assertThat(ctx.input().asString()).isEqualTo("content");
    }
}
