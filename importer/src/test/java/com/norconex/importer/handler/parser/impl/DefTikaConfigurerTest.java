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

import java.util.List;

import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.CompositeParser;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.ParserDecorator;
import org.apache.tika.parser.sentiment.SentimentAnalysisParser;
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

    // Sentiment analysis is disabled by default because
    // SentimentAnalysisParser downloads a model from the network the
    // moment it is initialized. It must therefore be entirely absent from
    // the resulting parser tree unless explicitly enabled, so no such
    // network call is ever attempted on a default configuration.
    @Test
    void testSentimentAnalysisDisabledByDefault() throws Exception {
        var tikaConfig = DefTikaConfigurer.configure(new DefaultParserConfig());
        var tikaParser = new AutoDetectParser(tikaConfig);
        assertThat(containsParser(tikaParser, SentimentAnalysisParser.class))
                .isFalse();
    }

    private boolean containsParser(
            Parser parser, Class<? extends Parser> targetClass)
            throws ReflectiveOperationException {
        var unwrapped = parser;
        while (unwrapped instanceof ParserDecorator decorator) {
            unwrapped = decorator.getWrappedParser();
        }
        if (targetClass.isInstance(unwrapped)) {
            return true;
        }
        if (unwrapped instanceof CompositeParser composite) {
            var field = CompositeParser.class.getDeclaredField("parsers");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Parser> children = (List<Parser>) field.get(composite);
            for (Parser child : children) {
                if (containsParser(child, targetClass)) {
                    return true;
                }
            }
        }
        return false;
    }
}
