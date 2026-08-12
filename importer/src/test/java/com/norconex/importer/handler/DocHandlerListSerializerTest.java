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
package com.norconex.importer.handler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.bean.BeanMapper.Format;
import com.norconex.importer.ImporterConfig;

/**
 * <p>
 * Round-trip coverage for handler lists, whose serialization is hand-written
 * (see {@link DocHandlerListSerializer} and
 * {@link DocHandlerListDeserializer}) rather than derived from annotations.
 * </p>
 * <p>
 * The XML writer names elements one of two ways depending on generator state,
 * so shapes are exercised in combination — a conditional first in the list
 * behaves differently from one that follows a plain handler, and the same
 * applies to conditions nested inside a group.
 * </p>
 */
class DocHandlerListSerializerTest {

    private static final String PLAIN_HANDLER = """
            handlers:
              - handler: {class: DebugTransformer}
            """;
    private static final String TWO_PLAIN_HANDLERS = """
            handlers:
              - handler: {class: DebugTransformer}
              - handler: {class: UuidTransformer}
            """;
    private static final String IF_ONLY = """
            handlers:
              - if:
                  condition: {class: BlankCondition, fieldMatcher: {pattern: title}}
                  then:
                    - handler: {class: Reject}
            """;
    private static final String IF_ELSE = """
            handlers:
              - if:
                  condition: {class: BlankCondition, fieldMatcher: {pattern: title}}
                  then:
                    - handler: {class: Reject}
                  else:
                    - handler: {class: DebugTransformer}
            """;
    private static final String HANDLER_THEN_IF = """
            handlers:
              - handler: {class: DebugTransformer}
              - if:
                  condition: {class: BlankCondition, fieldMatcher: {pattern: title}}
                  then:
                    - handler: {class: Reject}
            """;
    private static final String IF_THEN_HANDLER = """
            handlers:
              - if:
                  condition: {class: BlankCondition, fieldMatcher: {pattern: title}}
                  then:
                    - handler: {class: Reject}
              - handler: {class: DebugTransformer}
            """;
    private static final String NESTED_IF = """
            handlers:
              - if:
                  condition: {class: BlankCondition, fieldMatcher: {pattern: a}}
                  then:
                    - if:
                        condition: {class: BlankCondition, fieldMatcher: {pattern: b}}
                        then:
                          - handler: {class: Reject}
            """;
    private static final String IF_NOT = """
            handlers:
              - ifNot:
                  condition: {class: BlankCondition, fieldMatcher: {pattern: title}}
                  then:
                    - handler: {class: Reject}
            """;
    private static final String CONDITION_GROUP = """
            handlers:
              - if:
                  condition:
                    allOf:
                      - {class: BlankCondition, fieldMatcher: {pattern: a}}
                      - {class: BlankCondition, fieldMatcher: {pattern: b}}
                  then:
                    - handler: {class: Reject}
            """;
    private static final String GROUP_AFTER_HANDLER = """
            handlers:
              - handler: {class: DebugTransformer}
              - if:
                  condition:
                    anyOf:
                      - {class: BlankCondition, fieldMatcher: {pattern: a}}
                      - {class: BlankCondition, fieldMatcher: {pattern: b}}
                  then:
                    - handler: {class: Reject}
            """;

    static List<org.junit.jupiter.params.provider.Arguments> shapes() {
        return List.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "plain handler", PLAIN_HANDLER, 1),
                org.junit.jupiter.params.provider.Arguments.of(
                        "two plain handlers", TWO_PLAIN_HANDLERS, 2),
                org.junit.jupiter.params.provider.Arguments.of(
                        "if with then only", IF_ONLY, 1),
                org.junit.jupiter.params.provider.Arguments.of(
                        "if with then and else", IF_ELSE, 1),
                org.junit.jupiter.params.provider.Arguments.of(
                        "handler followed by if", HANDLER_THEN_IF, 2),
                org.junit.jupiter.params.provider.Arguments.of(
                        "if followed by handler", IF_THEN_HANDLER, 2),
                org.junit.jupiter.params.provider.Arguments.of(
                        "if nested in then", NESTED_IF, 1),
                org.junit.jupiter.params.provider.Arguments.of(
                        "ifNot", IF_NOT, 1),
                org.junit.jupiter.params.provider.Arguments.of(
                        "condition group", CONDITION_GROUP, 1),
                org.junit.jupiter.params.provider.Arguments.of(
                        "condition group after handler",
                        GROUP_AFTER_HANDLER, 2));
    }

    @ParameterizedTest(name = "{0} [JSON]")
    @MethodSource("shapes")
    void testRoundTripJson(String name, String yaml, int handlerCount) {
        assertRoundTrip(yaml, handlerCount, Format.JSON);
    }

    @ParameterizedTest(name = "{0} [YAML]")
    @MethodSource("shapes")
    void testRoundTripYaml(String name, String yaml, int handlerCount) {
        assertRoundTrip(yaml, handlerCount, Format.YAML);
    }

    @ParameterizedTest(name = "{0} [XML]")
    @MethodSource("shapes")
    void testRoundTripXml(String name, String yaml, int handlerCount) {
        assertRoundTrip(yaml, handlerCount, Format.XML);
    }

    @ParameterizedTest
    @EnumSource(value = Format.class, names = { "JSON", "YAML" })
    void testEmptyHandlerList(Format format) {
        var config = new ImporterConfig().setHandlers(List.of());
        assertThat(writeThenRead(config, format).getHandlers()).isEmpty();
    }

    /**
     * Per the configuration semantics, an empty list in XML is written
     * self-closing ({@code <handlers/>}); an empty element pair means an empty
     * string, which is not a list. Reading the documented form must yield an
     * empty handler list.
     */
    @Test
    void testEmptyHandlerListXml() {
        var config = BeanMapper.DEFAULT.read(ImporterConfig.class,
                new StringReader("<importer><handlers/></importer>"),
                Format.XML);
        assertThat(config.getHandlers()).isEmpty();
    }

    @Test
    void testHandlersPreserveOrder() {
        var config = read(TWO_PLAIN_HANDLERS);
        for (Format format : Format.values()) {
            assertThat(writeThenRead(config, format).getHandlers())
                    .as("order preserved in %s", format)
                    .containsExactlyElementsOf(config.getHandlers());
        }
    }

    /**
     * The XML writer must emit the handler list even when it happens to equal
     * the default, so that reading it back cannot silently fall back to the
     * default for a different reason.
     */
    @Test
    void testNonDefaultHandlerSurvivesXml() {
        var config = read(PLAIN_HANDLER);
        var xml = write(config, Format.XML);
        assertThat(xml).contains("DebugTransformer");
    }

    private void assertRoundTrip(
            String yaml, int expectedCount, Format format) {
        var config = read(yaml);
        assertThat(config.getHandlers()).hasSize(expectedCount);

        var result = writeThenRead(config, format);
        assertThat(result.getHandlers())
                .as("handler count after %s round trip", format)
                .hasSize(expectedCount);
        assertThat(result.getHandlers())
                .as("handlers after %s round trip", format)
                .isEqualTo(config.getHandlers());
    }

    private ImporterConfig read(String yaml) {
        return BeanMapper.DEFAULT.read(
                ImporterConfig.class, new StringReader(yaml), Format.YAML);
    }

    private String write(ImporterConfig config, Format format) {
        var writer = new StringWriter();
        BeanMapper.DEFAULT.write(config, writer, format);
        return writer.toString();
    }

    private ImporterConfig writeThenRead(
            ImporterConfig config, Format format) {
        return BeanMapper.DEFAULT.read(ImporterConfig.class,
                new StringReader(write(config, format)), format);
    }
}
