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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.norconex.commons.lang.ClassFinder;
import com.norconex.commons.lang.Operator;
import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.bean.BeanMapper.Format;
import com.norconex.commons.lang.config.Configurable;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.ImporterConfig;
import com.norconex.importer.TestUtil;
import com.norconex.importer.handler.condition.AllOf;
import com.norconex.importer.handler.condition.AnyOf;
import com.norconex.importer.handler.condition.Condition;
import com.norconex.importer.handler.condition.NoneOf;
import com.norconex.importer.handler.condition.impl.BlankCondition;
import com.norconex.importer.handler.condition.impl.DateCondition;
import com.norconex.importer.handler.condition.impl.DateValueMatcher;
import com.norconex.importer.handler.condition.impl.NumericCondition;
import com.norconex.importer.handler.condition.impl.NumericValueMatcher;
import com.norconex.importer.handler.condition.impl.ReferenceCondition;
import com.norconex.importer.handler.condition.impl.TextCondition;
import com.norconex.importer.handler.transformer.impl.Constant;
import com.norconex.importer.handler.transformer.impl.ConstantTransformer;
import com.norconex.importer.handler.transformer.impl.DebugTransformer;

/**
 * A handler can say, on itself, the conditions it applies under:
 * <code>when</code> and <code>unless</code>.
 */
@Timeout(30)
class HandlerWhenUnlessTest {

    private static final String FIELD = "added";

    // ---- (de)serialization --------------------------------------------

    private static Condition blank(String field) {
        var c = new BlankCondition();
        c.getConfiguration().setFieldMatcher(TextMatcher.basic(field));
        return c;
    }

    private static Condition reference(String regex) {
        var c = new ReferenceCondition();
        c.getConfiguration().setValueMatcher(TextMatcher.regex(regex));
        return c;
    }

    private static Condition text(String field, String value) {
        var c = new TextCondition();
        c.getConfiguration().setFieldMatcher(TextMatcher.basic(field));
        c.getConfiguration().setValueMatcher(TextMatcher.basic(value));
        return c;
    }

    private static Condition number() {
        var c = new NumericCondition();
        c.getConfiguration().setFieldMatcher(TextMatcher.basic("size"));
        c.getConfiguration().setValueMatcher(
                new NumericValueMatcher(Operator.GREATER_THAN, 5));
        return c;
    }

    private static Condition date() {
        var c = new DateCondition();
        c.getConfiguration().setFieldMatcher(TextMatcher.basic("modified"));
        c.getConfiguration().setValueMatcher(
                new DateValueMatcher(Operator.LOWER_THAN, "2024-01-31", null));
        return c;
    }

    static Stream<Arguments> conditions() {
        return Stream.of(
                Arguments.of("blank", blank("title")),
                Arguments.of("reference", reference(".*[.]pdf")),
                Arguments.of("text", text("document.contentType", "pdf")),
                Arguments.of("number", number()),
                Arguments.of("date", date()),
                Arguments.of("allOf", new AllOf(
                        List.of(blank("a"), reference(".*")))),
                Arguments.of("anyOf", new AnyOf(
                        List.of(blank("a"), text("b", "c")))),
                Arguments.of("noneOf", new NoneOf(List.of(blank("a")))),
                Arguments.of("group in group", new AllOf(List.of(
                        blank("a"),
                        new AnyOf(List.of(number(), reference("x")))))));
    }

    @ParameterizedTest(name = "{0} as when [{2}]")
    @MethodSource("conditionsAndFormats")
    void aWhenConditionSurvivesAWriteAndRead(
            String name, Condition condition, Format format) {
        var handler = new DebugTransformer();
        handler.getConfiguration().setWhen(condition);

        var read = writeThenRead(handler, format);

        assertThat(config(read).getWhen()).isEqualTo(condition);
        assertThat(config(read).getUnless()).isNull();
    }

    @ParameterizedTest(name = "{0} as unless [{2}]")
    @MethodSource("conditionsAndFormats")
    void anUnlessConditionSurvivesAWriteAndRead(
            String name, Condition condition, Format format) {
        var handler = new DebugTransformer();
        handler.getConfiguration().setUnless(condition);

        var read = writeThenRead(handler, format);

        assertThat(config(read).getUnless()).isEqualTo(condition);
        assertThat(config(read).getWhen()).isNull();
    }

    static Stream<Arguments> conditionsAndFormats() {
        return conditions().flatMap(a -> Stream.of(Format.values()).map(
                f -> Arguments.of(a.get()[0], a.get()[1], f)));
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void whenAndUnlessTogetherSurviveAWriteAndRead(Format format) {
        var handler = new DebugTransformer();
        handler.getConfiguration()
                .setWhen(reference(".*[.]html"))
                .setUnless(blank("title"));

        var read = writeThenRead(handler, format);

        assertThat(config(read).getWhen()).isEqualTo(reference(".*[.]html"));
        assertThat(config(read).getUnless()).isEqualTo(blank("title"));
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void neitherIsWrittenWhenNoneIsSet(Format format) {
        var written = write(new DebugTransformer(), format);

        assertThat(written).doesNotContain("when").doesNotContain("unless");
        assertThat(config(writeThenRead(new DebugTransformer(), format))
                .getWhen()).isNull();
    }

    @Test
    void whenAndUnlessCanBeWrittenByHandInYaml() {
        var config = BeanMapper.DEFAULT.read(ImporterConfig.class,
                new StringReader("""
                        handlers:
                          - handler:
                              class: DebugTransformer
                              when:
                                class: ReferenceCondition
                                valueMatcher: {pattern: '.*[.]pdf', method: REGEX}
                              unless:
                                allOf:
                                  - {class: BlankCondition, fieldMatcher: {pattern: a}}
                                  - {class: BlankCondition, fieldMatcher: {pattern: b}}
                        """), Format.YAML);

        var cfg = config(config.getHandlers().get(0));
        assertThat(cfg.getWhen()).isInstanceOf(ReferenceCondition.class);
        assertThat(cfg.getUnless()).isInstanceOf(AllOf.class);
        assertThat(((AllOf) cfg.getUnless()).getConditions()).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void aHandlerNextToAnIfKeepsItsWhenInAFlow(Format format) {
        var plain = new DebugTransformer();
        plain.getConfiguration().setWhen(blank("title"));
        var inside = new DebugTransformer();
        inside.getConfiguration().setUnless(blank("x"));
        var branch = new com.norconex.importer.handler.condition.If(
                reference(".*"));
        branch.setThenHandlers(List.of(inside));
        var importer = new ImporterConfig().setHandlers(List.of(plain, branch));

        var read = BeanMapper.DEFAULT.read(ImporterConfig.class,
                new StringReader(write(importer, format)), format);

        assertThat(config(read.getHandlers().get(0)).getWhen())
                .isEqualTo(blank("title"));
        var then = ((com.norconex.importer.handler.condition.If) read
                .getHandlers().get(1)).getThenHandlers();
        assertThat(config(then.get(0)).getUnless()).isEqualTo(blank("x"));
    }

    // ---- behaviour ------------------------------------------------------

    private static boolean ran(DocHandler handler, Properties meta)
            throws IOException {
        var ctx = TestUtil.newHandlerContext(
                "http://example.com/a.pdf",
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)),
                meta);
        assertThat(ctx.executeDocHandler(handler)).isTrue();
        return ctx.metadata().containsKey(FIELD);
    }

    private static ConstantTransformer adder() {
        var t = new ConstantTransformer();
        t.getConfiguration().setConstants(List.of(Constant.of(FIELD, "yes")));
        return t;
    }

    @Test
    void aHandlerWithNoConditionRunsAsBefore() throws IOException {
        assertThat(ran(adder(), new Properties())).isTrue();
    }

    @Test
    void aHandlerWithWhenRunsOnlyWhenItHolds() throws IOException {
        var handler = adder();
        handler.getConfiguration().setWhen(reference(".*[.]pdf"));
        assertThat(ran(handler, new Properties())).isTrue();

        handler.getConfiguration().setWhen(reference(".*[.]docx"));
        assertThat(ran(handler, new Properties())).isFalse();
    }

    @Test
    void aHandlerWithUnlessRunsOnlyWhenItDoesNotHold() throws IOException {
        var handler = adder();
        handler.getConfiguration().setUnless(reference(".*[.]pdf"));
        assertThat(ran(handler, new Properties())).isFalse();

        handler.getConfiguration().setUnless(reference(".*[.]docx"));
        assertThat(ran(handler, new Properties())).isTrue();
    }

    @Test
    void withBothTheDocumentMustMeetWhenAndNotUnless() throws IOException {
        var handler = adder();
        handler.getConfiguration()
                .setWhen(reference(".*[.]pdf"))
                .setUnless(text("skip", "yes"));

        var skipped = new Properties();
        skipped.set("skip", "yes");
        assertThat(ran(handler, skipped)).isFalse();
        assertThat(ran(handler, new Properties())).isTrue();

        handler.getConfiguration().setWhen(reference(".*[.]docx"));
        assertThat(ran(handler, new Properties())).isFalse();
    }

    @Test
    void aGroupWorksAsTheCondition() throws IOException {
        var handler = adder();
        handler.getConfiguration().setWhen(new AnyOf(List.of(
                reference(".*[.]docx"), blank("nothere"))));
        assertThat(ran(handler, new Properties())).isTrue();

        handler.getConfiguration().setWhen(new AllOf(List.of(
                reference(".*[.]pdf"), blank("title"))));
        var withTitle = new Properties();
        withTitle.set("title", "t");
        assertThat(ran(handler, withTitle)).isFalse();
    }

    @Test
    void aSkippedHandlerPassesTheDocumentOnUntouched() throws IOException {
        var handler = adder();
        handler.getConfiguration().setWhen(reference(".*[.]docx"));
        var ctx = TestUtil.newHandlerContext("http://example.com/a.pdf",
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)),
                new Properties());

        assertThat(ctx.executeDocHandler(handler)).isTrue();

        assertThat(ctx.metadata().isEmpty()).isTrue();
        assertThat(ctx.isRejected()).isFalse();
    }

    @Test
    void rejectingOnlyWhenAConditionHolds() throws IOException {
        var reject = new Reject();
        reject.getConfiguration().setWhen(reference(".*[.]pdf"));
        var ctx = TestUtil.newHandlerContext("http://example.com/a.pdf",
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)),
                new Properties());
        assertThat(ctx.executeDocHandler(reject)).isFalse();
        assertThat(ctx.isRejected()).isTrue();

        reject.getConfiguration().setWhen(reference(".*[.]docx"));
        var other = TestUtil.newHandlerContext("http://example.com/a.pdf",
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)),
                new Properties());
        assertThat(other.executeDocHandler(reject)).isTrue();
        assertThat(other.isRejected()).isFalse();
    }

    /**
     * Every handler that has a configuration honours it, so none can be left
     * out by a handler that does not go through the importer's loop.
     */
    @Test
    void everyConfigurableHandlerHonoursWhen() throws Exception {
        var handlerTypes = ClassFinder.findSubTypes(DocHandler.class).stream()
                .filter(t -> !t.isInterface()
                        && !Modifier.isAbstract(t.getModifiers()))
                .filter(t -> Configurable.class.isAssignableFrom(t))
                // not the handlers other tests define for their own purposes
                .filter(t -> t.getEnclosingClass() == null
                        && !t.getName().contains("Test"))
                .toList();
        assertThat(handlerTypes).hasSizeGreaterThan(20);

        var withoutBase = new ArrayList<String>();
        for (var type : handlerTypes) {
            DocHandler handler;
            try {
                handler = (DocHandler) type.getDeclaredConstructor()
                        .newInstance();
            } catch (NoSuchMethodException e) {
                continue; // needs arguments: not built from configuration
            }
            var cfg = ((Configurable<?>) handler).getConfiguration();
            if (!(cfg instanceof BaseDocHandlerConfig base)) {
                withoutBase.add(type.getSimpleName());
                continue;
            }
            // A condition that never holds: the handler must not even start.
            base.setWhen(reference("will-not-match"));
            var ctx = TestUtil.newHandlerContext("http://example.com/a.pdf",
                    new ByteArrayInputStream(
                            "x".getBytes(StandardCharsets.UTF_8)),
                    new Properties());
            assertThat(ctx.executeDocHandler(handler))
                    .as(type.getSimpleName()).isTrue();
            assertThat(ctx.isRejected()).as(type.getSimpleName()).isFalse();
            assertThat(ctx.metadata().isEmpty())
                    .as(type.getSimpleName()).isTrue();
        }
        assertThat(withoutBase)
                .as("handlers whose configuration is not a BaseDocHandlerConfig")
                .isEmpty();
    }

    // ---- helpers --------------------------------------------------------

    private static BaseDocHandlerConfig config(DocHandler handler) {
        return (BaseDocHandlerConfig) ((com.norconex.commons.lang.config.Configurable<
                ?>) handler)
                        .getConfiguration();
    }

    private static String write(Object bean, Format format) {
        var writer = new StringWriter();
        BeanMapper.DEFAULT.write(bean, writer, format);
        return writer.toString();
    }

    private static String write(DocHandler handler, Format format) {
        return write(new ImporterConfig().setHandlers(List.of(handler)),
                format);
    }

    private static DocHandler writeThenRead(DocHandler handler, Format format) {
        var read = BeanMapper.DEFAULT.read(ImporterConfig.class,
                new StringReader(write(handler, format)), format);
        assertThat(read.getHandlers()).hasSize(1);
        return read.getHandlers().get(0);
    }
}
