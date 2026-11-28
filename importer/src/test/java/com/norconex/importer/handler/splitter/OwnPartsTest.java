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
package com.norconex.importer.handler.splitter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.importer.Importer;
import com.norconex.importer.ImporterConfig;
import com.norconex.importer.ImporterRequest;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.handler.HandlerPositions;
import com.norconex.importer.handler.condition.If;
import com.norconex.importer.response.ImporterResponse;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Every part a splitter makes goes through all the handlers again; a splitter
 * leaves the parts it made itself as they are, unless told to apply to them.
 */
@Timeout(30)
class OwnPartsTest {

    @Test
    void aSplitterLeavesItsOwnPartsAsTheyAre() {
        var refs = parts(new CommaSplitter());

        // "2,3" came back through the flow, and was not split again.
        assertThat(refs).containsExactly("doc|0", "doc|1");
    }

    @Test
    void aSplitterCanApplyToItsOwnParts() {
        var splitter = new CommaSplitter();
        splitter.getConfiguration().setApplyToOwnParts(true);

        assertThat(parts(splitter)).containsExactly(
                "doc|0", "doc|1", "doc|1|0", "doc|1|1");
    }

    @Test
    void aSplitterSplitsThePartsOfAnotherAndLeavesTheirPartsAlone() {
        // Two splitters alike but for where they are: the second splits
        // the first's parts, and neither splits the parts that makes.
        var first = new CommaSplitter();
        first.getConfiguration().setDiscardOriginal(true);
        var second = new CommaSplitter();

        assertThat(parts(first, second)).containsExactly(
                "doc|0", "doc|1", "doc|1|0", "doc|1|1");
    }

    @Test
    void aSplitterInsideAConditionIsToldApartByWhereItIs() {
        var first = new CommaSplitter();
        first.getConfiguration().setDiscardOriginal(true);
        var second = new CommaSplitter();
        var cond = new If();
        cond.setCondition(ctx -> true);
        cond.setThenHandlers(List.of(second));

        assertThat(parts(first, cond)).containsExactly(
                "doc|0", "doc|1", "doc|1|0", "doc|1|1");
    }

    @Test
    void positionsFollowTheConfiguration() {
        var a = new CommaSplitter();
        var b = new CommaSplitter();
        var c = new CommaSplitter();
        var cond = new If();
        cond.setThenHandlers(List.of(b));
        cond.setElseHandlers(List.of(c));

        var positions = HandlerPositions.of(List.of(a, cond));

        assertThat(positions.get(a)).isEqualTo("0");
        assertThat(positions.get(cond)).isEqualTo("1");
        assertThat(positions.get(b)).isEqualTo("1.then.0");
        assertThat(positions.get(c)).isEqualTo("1.else.0");
    }

    @Test
    void applyingToOwnPartsIsOffByDefaultLikeEveryOtherBoolean() {
        assertThat(new BaseDocumentSplitterConfig().isApplyToOwnParts())
                .isFalse();
    }

    /** The references of all the parts made, depth first. */
    private static List<String> parts(DocHandler... handlers) {
        var config = new ImporterConfig().setHandlers(List.of(handlers));
        try (var importer = new Importer(config)) {
            var response = importer.importDocument(new ImporterRequest(
                    new ByteArrayInputStream(
                            "1,2,3".getBytes(StandardCharsets.UTF_8)))
                                    .setReference("doc"));
            var refs = new ArrayList<String>();
            collect(response, refs);
            return refs;
        }
    }

    private static void collect(ImporterResponse response, List<String> refs) {
        for (var nested : response.getNestedResponses()) {
            refs.add(nested.getReference());
            collect(nested, refs);
        }
    }

    /** Splits "a,b,c" in two: "a" and "b,c". */
    @Data
    @EqualsAndHashCode(callSuper = false)
    static class CommaSplitter
            extends AbstractDocumentSplitter<BaseDocumentSplitterConfig> {
        private final BaseDocumentSplitterConfig configuration =
                new BaseDocumentSplitterConfig();

        @Override
        public void split(DocHandlerContext docCtx) throws IOException {
            var text = docCtx.input().asString();
            if (!text.contains(",")) {
                return;
            }
            var parts = List.of(StringUtils.substringBefore(text, ","),
                    StringUtils.substringAfter(text, ","));
            for (var i = 0; i < parts.size(); i++) {
                docCtx.childDocs().add(new Doc(docCtx.reference() + "|" + i)
                        .setInputStream(docCtx.streamFactory()
                                .newInputStream(parts.get(i))));
            }
        }
    }
}
