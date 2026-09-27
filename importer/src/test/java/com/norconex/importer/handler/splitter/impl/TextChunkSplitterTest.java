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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.importer.TestUtil;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.handler.DocHandlerException;

@Timeout(30)
class TextChunkSplitterTest {

    @Test
    void testShortTextIsNotSplit() throws IOException {
        var splitter = new TextChunkSplitter();
        splitter.getConfiguration().setMaxChunkSize(1000);

        var docCtx = TestUtil.newHandlerContext("Just a short sentence.");
        var keepGoing = splitter.handle(docCtx);

        assertThat(docCtx.childDocs()).isEmpty();
        // Not split, so the original must survive to the next handler.
        assertThat(keepGoing).isTrue();
        assertThat(docCtx.isRejected()).isFalse();
    }

    @Test
    void testOriginalIsDiscardedByDefaultOnceSplit() throws IOException {
        var splitter = new TextChunkSplitter();
        splitter.getConfiguration().setMaxChunkSize(50).setChunkOverlap(0);

        var docCtx = TestUtil.newHandlerContext(
                "Paragraph number one is here.\n\n"
                        + "Paragraph number two is here.\n\n"
                        + "Paragraph number three is here.");
        var keepGoing = splitter.handle(docCtx);

        assertThat(docCtx.childDocs()).hasSizeGreaterThan(1);
        assertThat(keepGoing).isFalse();
        assertThat(docCtx.isRejected()).isTrue();
    }

    @Test
    void testOriginalCanBeKeptOnceSplit() throws IOException {
        var splitter = new TextChunkSplitter();
        splitter.getConfiguration()
                .setMaxChunkSize(50)
                .setChunkOverlap(0)
                .setDiscardOriginal(false);

        var docCtx = TestUtil.newHandlerContext(
                "Paragraph number one is here.\n\n"
                        + "Paragraph number two is here.\n\n"
                        + "Paragraph number three is here.");
        var keepGoing = splitter.handle(docCtx);

        assertThat(docCtx.childDocs()).hasSizeGreaterThan(1);
        assertThat(keepGoing).isTrue();
        assertThat(docCtx.isRejected()).isFalse();
    }

    @Test
    void testLongTextIsSplitIntoMultipleChunks() throws IOException {
        var splitter = new TextChunkSplitter();
        splitter.getConfiguration()
                .setMaxChunkSize(50)
                .setChunkOverlap(0);

        // Three paragraphs, each comfortably under 50 characters, so the
        // splitter should be able to break cleanly between them.
        var text = "Paragraph number one is here.\n\n"
                + "Paragraph number two is here.\n\n"
                + "Paragraph number three is here.";

        var docCtx = TestUtil.newHandlerContext(text);
        splitter.handle(docCtx);

        var chunks = docCtx.childDocs();
        assertThat(chunks).hasSizeGreaterThan(1);

        for (var i = 0; i < chunks.size(); i++) {
            var chunk = chunks.get(i);
            assertThat(chunk.getParentReferences()).contains(
                    docCtx.reference());
            assertThat(chunk.getMetadata().getInteger(
                    TextChunkSplitter.DOC_CHUNK_INDEX)).isEqualTo(i + 1);
            assertThat(chunk.getMetadata().getInteger(
                    TextChunkSplitter.DOC_CHUNK_COUNT))
                            .isEqualTo(chunks.size());
        }
    }

    @Test
    void testOverlapRepeatsTrailingCharacters() throws IOException {
        var text = "Paragraph number one is here.\n\n"
                + "Paragraph number two is here.\n\n"
                + "Paragraph number three is here.";

        // Baseline: the clean, non-overlapping boundaries.
        var noOverlap = new TextChunkSplitter();
        noOverlap.getConfiguration().setMaxChunkSize(50).setChunkOverlap(0);
        var baselineCtx = TestUtil.newHandlerContext(text);
        noOverlap.handle(baselineCtx);
        var baselineChunks = baselineCtx.childDocs();
        assertThat(baselineChunks).hasSizeGreaterThan(1);

        // Same input, with overlap enabled.
        var withOverlap = new TextChunkSplitter();
        withOverlap.getConfiguration().setMaxChunkSize(50)
                .setChunkOverlap(10);
        var overlapCtx = TestUtil.newHandlerContext(text);
        withOverlap.handle(overlapCtx);
        var overlapChunks = overlapCtx.childDocs();

        assertThat(overlapChunks).hasSameSizeAs(baselineChunks);
        for (var i = 1; i < overlapChunks.size(); i++) {
            var previousBaseline = textOf(baselineChunks.get(i - 1));
            var expectedTail = previousBaseline.substring(
                    Math.max(0, previousBaseline.length() - 10));
            assertThat(textOf(overlapChunks.get(i))).startsWith(expectedTail);
        }
    }

    private String textOf(Doc doc) throws IOException {
        return IOUtils.toString(
                doc.getInputStream(), StandardCharsets.UTF_8);
    }

    @Test
    void testNonPositiveChunkSizeIsRejectedRatherThanHanging() {
        // A size of 0 used to send TextReader into an endless loop of empty
        // chunks. The class-level timeout would turn a regression into a
        // failure instead of a hung build.
        for (var size : new int[] { 0, -1, -50 }) {
            var splitter = new TextChunkSplitter();
            splitter.getConfiguration().setMaxChunkSize(size);
            var docCtx = TestUtil.newHandlerContext("Some text to split.");

            assertThatExceptionOfType(DocHandlerException.class)
                    .isThrownBy(() -> splitter.handle(docCtx))
                    .withMessageContaining("maxChunkSize");
        }
    }

    @Test
    void testBlankReferencePrefixFallsBackToDefault() throws IOException {
        var splitter = new TextChunkSplitter();
        splitter.getConfiguration()
                .setMaxChunkSize(50)
                .setChunkOverlap(0)
                .setReferenceChunkPrefix("");

        var docCtx = TestUtil.newHandlerContext(
                "http://example.com/doc",
                "Paragraph number one is here.\n\n"
                        + "Paragraph number two is here.\n\n"
                        + "Paragraph number three is here.");
        splitter.handle(docCtx);

        assertThat(docCtx.childDocs()).isNotEmpty();
        assertThat(docCtx.childDocs().get(0).getReference())
                .isEqualTo("http://example.com/doc#chunk1");
    }

    @Test
    void testCustomReferencePrefixIsUsed() throws IOException {
        var splitter = new TextChunkSplitter();
        splitter.getConfiguration()
                .setMaxChunkSize(50)
                .setChunkOverlap(0)
                .setReferenceChunkPrefix("#part");

        var docCtx = TestUtil.newHandlerContext(
                "http://example.com/doc",
                "Paragraph number one is here.\n\n"
                        + "Paragraph number two is here.\n\n"
                        + "Paragraph number three is here.");
        splitter.handle(docCtx);

        assertThat(docCtx.childDocs().get(0).getReference())
                .isEqualTo("http://example.com/doc#part1");
    }

    @Test
    void testWriteRead() {
        var splitter = new TextChunkSplitter();
        splitter.getConfiguration()
                .setMaxChunkSize(500)
                .setChunkOverlap(50)
                .setReferenceChunkPrefix("#part");
        assertThatNoException().isThrownBy(
                () -> BeanMapper.DEFAULT.assertWriteRead(splitter));
    }
}
