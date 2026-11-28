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
package com.norconex.importer.handler.splitter.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.TestUtil;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.doc.DocMetaConstants;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.splitter.BaseDocumentSplitterConfig;

/**
 * A document split in parts passes its metadata on to each part, unless the
 * splitter is told not to; a part always keeps what the splitter itself sets.
 */
@Timeout(30)
class SplitterParentMetadataTest {

    private static final String FIELD = "department";

    /**
     * Each splitter, made to ignore the parent's metadata or not, and the
     * document it splits in more than one part.
     */
    static Stream<Arguments> splitters() {
        BiFunction<Boolean, BaseDocumentSplitterConfig,
                BaseDocumentSplitterConfig> ignore = (on, cfg) -> {
                    if (on) {
                        cfg.getParentMetadataMatcher().setPattern("");
                    }
                    return cfg;
                };
        return Stream.of(
                Arguments.of("csv", (Splitter) on -> {
                    var s = new CsvSplitter();
                    ignore.apply(on, s.getConfiguration());
                    return s;
                }, (Input) () -> text("a,b\nc,d\ne,f")),
                Arguments.of("dom", (Splitter) on -> {
                    var s = new DomSplitter();
                    ignore.apply(on, s.getConfiguration().setSelector("p"));
                    return s;
                }, (Input) () -> text(
                        "<html><body><p>one</p><p>two</p></body></html>")),
                Arguments.of("xml", (Splitter) on -> {
                    var s = new XmlStreamSplitter();
                    ignore.apply(on, s.getConfiguration().setPath("/a/b")
                            .setContentTypeMatcher(TextMatcher.regex(".*")));
                    return s;
                }, (Input) () -> text("<a><b>one</b><b>two</b></a>")),
                Arguments.of("pdf", (Splitter) on -> {
                    var s = new PdfPageSplitter();
                    ignore.apply(on, s.getConfiguration());
                    return s;
                }, (Input) () -> PdfPageSplitterTest.class.getResourceAsStream(
                        "PdfPageSplitterTest.pdf")),
                Arguments.of("chunks", (Splitter) on -> {
                    var s = new TextChunkSplitter();
                    s.getConfiguration().setMaxChunkSize(50).setChunkOverlap(0);
                    if (on) {
                        s.getConfiguration().getParentMetadataMatcher()
                                .setPattern("");
                    }
                    return s;
                }, (Input) () -> text("Paragraph number one is here.\n\n"
                        + "Paragraph number two is here.\n\n"
                        + "Paragraph number three is here.")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("splitters")
    void partsGetTheParentsMetadataByDefault(
            String name, Splitter splitter, Input input) throws IOException {
        var parts = split(splitter.make(false), input);

        assertThat(parts).hasSizeGreaterThan(1)
                .allSatisfy(part -> assertThat(
                        part.getMetadata().getString(FIELD))
                                .isEqualTo("Sales"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("splitters")
    void partsCanStartWithoutTheParentsMetadata(
            String name, Splitter splitter, Input input) throws IOException {
        var parts = split(splitter.make(true), input);

        assertThat(parts).hasSizeGreaterThan(1).allSatisfy(part -> {
            assertThat(part.getMetadata().containsKey(FIELD)).isFalse();
            // What the splitter sets on a part is there either way.
            assertThat(part.getMetadata().getString(
                    DocMetaConstants.EMBEDDED_REFERENCE)).isNotBlank();
        });
    }

    @Test
    void everyFieldIsCopiedByDefault() {
        assertThat(new CsvSplitter().getConfiguration()
                .getParentMetadataMatcher().getPattern()).isEqualTo(".*");
        assertThat(new TextChunkSplitterConfig().getParentMetadataMatcher()
                .getPattern()).isEqualTo(".*");
    }

    @Test
    void partsCanGetOnlyTheFieldsTheMatcherMatches() throws IOException {
        var splitter = new CsvSplitter();
        splitter.getConfiguration().getParentMetadataMatcher()
                .copyFrom(TextMatcher.regex("depart.*"));
        var meta = new Properties();
        meta.set(FIELD, "Sales");
        meta.set("secret", "x");
        try (var in = text("a,b\nc,d\ne,f")) {
            var ctx = TestUtil.newHandlerContext("doc", in, meta);
            splitter.handle(ctx);
            assertThat(ctx.childDocs()).hasSizeGreaterThan(1)
                    .allSatisfy(part -> {
                        assertThat(part.getMetadata().getString(FIELD))
                                .isEqualTo("Sales");
                        assertThat(part.getMetadata().containsKey("secret"))
                                .isFalse();
                    });
        }

    }

    private static List<Doc> split(DocHandler splitter, Input input)
            throws IOException {
        var meta = new Properties();
        meta.set(FIELD, "Sales");
        try (var in = input.open()) {
            var ctx = TestUtil.newHandlerContext("doc", in, meta);
            splitter.handle(ctx);
            return ctx.childDocs();
        }
    }

    private static InputStream text(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    interface Splitter {
        DocHandler make(boolean copyNoParentField);
    }

    @FunctionalInterface
    interface Input {
        InputStream open();
    }
}
