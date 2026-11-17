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
import static org.assertj.core.api.Assertions.assertThatException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import org.apache.tika.parser.CompositeParser;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.ParserDecorator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class DefTikaConfigurerTest {

    @Test
    void testDefaultConfig() {
        var cfg = new DefaultParserConfig();
        assertThatNoException()
                .isThrownBy(() -> DefTikaConfigurer.configure(cfg));
    }

    @Test
    void testBadConfig() {
        var cfg = new DefaultParserConfig();
        cfg.getOcrConfig().setDensity(-1000);
        assertThatException()
                .isThrownBy(() -> DefTikaConfigurer.configure(cfg));
    }

    // The journal parser relies on a Grobid service and must be absent
    // unless Grobid is explicitly enabled.
    @Test
    void testJournalParserExcludedUnlessGrobidEnabled() throws Exception {
        var cfg = new DefaultParserConfig();
        assertThat(containsParser(
                DefTikaConfigurer.configure(cfg), "JournalParser")).isFalse();
        cfg.getGrobidConfig().setEnabled(true);
        assertThat(containsParser(
                DefTikaConfigurer.configure(cfg), "JournalParser")).isTrue();
    }

    private boolean containsParser(Parser parser, String simpleName) {
        var unwrapped = parser;
        while (unwrapped instanceof ParserDecorator decorator) {
            unwrapped = decorator.getWrappedParser();
        }
        if (unwrapped.getClass().getSimpleName().equals(simpleName)) {
            return true;
        }
        if (unwrapped instanceof CompositeParser composite) {
            for (Parser child : composite.getAllComponentParsers()) {
                if (containsParser(child, simpleName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
