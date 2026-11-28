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
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.bean.BeanMapper.Format;
import com.norconex.commons.lang.config.Configurable;
import com.norconex.commons.lang.file.ContentType;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.ImporterConfig;
import com.norconex.importer.TestUtil;
import com.norconex.importer.handler.condition.impl.ContentTypeCondition;
import com.norconex.importer.handler.condition.impl.ReferenceCondition;
import com.norconex.importer.handler.splitter.impl.DomSplitter;
import com.norconex.importer.handler.splitter.impl.PdfPageSplitter;
import com.norconex.importer.handler.splitter.impl.XmlStreamSplitter;
import com.norconex.importer.handler.transformer.impl.DomTransformer;
import com.norconex.importer.handler.transformer.impl.ImageTransformer;

/**
 * Handlers made for some content types only start with a <code>when</code>
 * that says which, so there is one way to say it; setting their own
 * <code>when</code> replaces it.
 */
@Timeout(30)
class DefaultContentTypeWhenTest {

    record Case(String name, Supplier<DocHandler> make, String applies,
            String doesNotApply) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> handlers() {
        return Stream.of(
                new Case("DomTransformer", DomTransformer::new,
                        "text/html", "application/pdf"),
                new Case("DomSplitter", DomSplitter::new,
                        "application/xhtml+xml", "image/png"),
                new Case("XmlStreamSplitter", XmlStreamSplitter::new,
                        "application/xml", "text/plain"),
                new Case("PdfPageSplitter", PdfPageSplitter::new,
                        "application/pdf", "text/html"),
                new Case("ImageTransformer", ImageTransformer::new,
                        "image/png", "text/html"))
                .map(Arguments::of);
    }

    private static BaseDocHandlerConfig config(DocHandler handler) {
        return (BaseDocHandlerConfig) ((Configurable<?>) handler)
                .getConfiguration();
    }

    private static boolean applies(DocHandler handler, String type)
            throws IOException {
        var ctx = TestUtil.newHandlerContext("ref",
                new ByteArrayInputStream(new byte[0]),
                ContentType.valueOf(type));
        var when = config(handler).getWhen();
        return when == null || when.test(ctx);
    }

    @ParameterizedTest
    @MethodSource("handlers")
    void startsWithAWhenForItsContentTypes(Case c) throws IOException {
        var handler = c.make().get();

        assertThat(config(handler).getWhen())
                .isInstanceOf(ContentTypeCondition.class);
        assertThat(applies(handler, c.applies())).isTrue();
        assertThat(applies(handler, c.doesNotApply())).isFalse();
    }

    @ParameterizedTest
    @MethodSource("handlers")
    void aWhenOfItsOwnReplacesIt(Case c) throws IOException {
        var handler = c.make().get();
        config(handler).setWhen(new ContentTypeCondition(
                TextMatcher.basic(c.doesNotApply())));

        assertThat(applies(handler, c.doesNotApply())).isTrue();
        assertThat(applies(handler, c.applies())).isFalse();
    }

    @ParameterizedTest
    @MethodSource("handlers")
    void aWhenOfADifferentKindReplacesItToo(Case c) {
        var handler = c.make().get();
        var reference = new ReferenceCondition(TextMatcher.regex(".*"));
        config(handler).setWhen(reference);

        assertThat(config(handler).getWhen()).isSameAs(reference);
    }

    @ParameterizedTest
    @MethodSource("handlers")
    void theDefaultIsNotWrittenAndComesBackWhenRead(Case c) {
        for (var format : Format.values()) {
            var written = write(c.make().get(), format);
            assertThat(written).as("%s in %s", c.name(), format)
                    .doesNotContain("when").doesNotContain("ontentType");

            var read = read(written, format);
            assertThat(config(read).getWhen()).as(c.name())
                    .isEqualTo(config(c.make().get()).getWhen());
        }
    }

    @ParameterizedTest
    @MethodSource("handlers")
    void aWhenOfItsOwnSurvivesAWriteAndRead(Case c) {
        var own = new ContentTypeCondition(
                TextMatcher.regex("text/.*").ignoreCase());
        for (var format : Format.values()) {
            var handler = c.make().get();
            config(handler).setWhen(own);

            var read = read(write(handler, format), format);

            assertThat(config(read).getWhen()).as("%s in %s", c.name(), format)
                    .isEqualTo(own);
        }
    }

    @ParameterizedTest
    @EnumSource(Format.class)
    void aHandlerWrittenByHandWithNoWhenKeepsItsDefault(Format format) {
        var yaml = """
                handlers:
                  - handler:
                      class: PdfPageSplitter
                """;
        var handler = BeanMapper.DEFAULT.read(ImporterConfig.class,
                new StringReader(yaml), Format.YAML).getHandlers().get(0);
        assertThat(config(handler).getWhen())
                .isInstanceOf(ContentTypeCondition.class);

        var again = read(write(handler, format), format);
        assertThat(config(again).getWhen())
                .isEqualTo(config(handler).getWhen());
    }

    private static String write(DocHandler handler, Format format) {
        var writer = new StringWriter();
        BeanMapper.DEFAULT.write(
                new ImporterConfig().setHandlers(List.of(handler)),
                writer, format);
        return writer.toString();
    }

    private static DocHandler read(String source, Format format) {
        return BeanMapper.DEFAULT.read(ImporterConfig.class,
                new StringReader(source), format).getHandlers().get(0);
    }
}
