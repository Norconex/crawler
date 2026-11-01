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
package com.norconex.importer.handler.transformer.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.importer.TestUtil;
import com.norconex.importer.handler.DocHandlerContext;

@Timeout(30)
@SuppressWarnings("null")
class SaveDocumentTransformerTest {

    private static final String FLD_PATH = "file.path";

    @TempDir
    private Path tempDir;

    @Test
    void testSaveDocumentTransformer()
            throws IOException, IOException {
        var t = new SaveDocumentTransformer();
        t.getConfiguration()
                .setEscape(true)
                .setPathToField(FLD_PATH)
                .setSaveDir(tempDir.resolve("basic"));

        var doc = transform(t, "http://example.com/my$file.html");
        assertThat(savedFile(doc)).isRegularFile();
        transform(t, "/with/stripables/");
        assertThat(
                listFilePaths(
                        t.getConfiguration().getSaveDir()))
                                .containsExactlyInAnyOrder(
                                        "http/example.com/my_36_file.html",
                                        "with/stripables");
    }

    @Test
    void testParentIsFileConflict()
            throws IOException, IOException {
        var t = new SaveDocumentTransformer();
        t.getConfiguration()
                .setDefaultFileName("index.html")
                .setPathToField(FLD_PATH)
                .setSaveDir(tempDir.resolve("parentIsFile"));

        var doc1 = transform(t, "some/test");
        assertThat(savedFile(doc1)).isRegularFile();
        var doc2 = transform(t, "some/test/surprise");
        assertThat(savedFile(doc1)).isDirectory();
        assertThat(savedFile(doc1).resolve("index.html")).isRegularFile();
        assertThat(savedFile(doc2)).isRegularFile();

        assertThat(
                listFilePaths(
                        t.getConfiguration().getSaveDir()))
                                .containsExactlyInAnyOrder(
                                        "some/test/index.html",
                                        "some/test/surprise");
    }

    @Test
    void testTargetIsDirConflict()
            throws IOException, IOException {
        var t = new SaveDocumentTransformer();
        t.getConfiguration()
                .setDefaultFileName("index.html")
                .setPathToField(FLD_PATH)
                .setSaveDir(tempDir.resolve("targetIsDir"));

        var doc1 = transform(t, "some/test/file.txt");
        assertThat(savedFile(doc1)).isRegularFile();
        var doc2 = transform(t, "some/test");
        assertThat(savedFile(doc1)).isRegularFile();
        assertThat(savedFile(doc2).resolve("index.html")).isRegularFile();
        assertThat(savedFile(doc2)).isDirectory();

        assertThat(
                listFilePaths(
                        t.getConfiguration().getSaveDir()))
                                .containsExactlyInAnyOrder(
                                        "some/test/file.txt",
                                        "some/test/index.html");
    }

    @Test
    void testMaxPathLength()
            throws IOException, IOException {
        var t = new SaveDocumentTransformer();
        t.getConfiguration()
                .setDefaultFileName("index.html")
                .setPathToField(FLD_PATH)
                .setSaveDir(tempDir.resolve("maxLengthDir"));

        var saveDirLength = t.getConfiguration().getSaveDir()
                .toAbsolutePath().toString().length();

        // Max length is too short
        t.getConfiguration().setMaxPathLength(4);
        transform(t, "some/long/path/for/file1.txt");

        // Save dir path is too long to apply truncation
        t.getConfiguration().setMaxPathLength(saveDirLength + 4);
        transform(t, "some/long/path/for/file2.txt");

        assertThat(
                listFilePaths(
                        t.getConfiguration().getSaveDir()))
                                .containsExactlyInAnyOrder(
                                        "some/long/path/for/file1.txt",
                                        "some/long/path/for/file2.txt");

        // Apply truncation
        t.getConfiguration().setMaxPathLength(saveDirLength + 18);
        var docCtx = transform(t, "some/long/path/for/file3.txt");
        assertThat(savedFile(docCtx))
                .isRegularFile()
                .matches(p -> p.getFileName().toString().matches("^lo\\d+$"));
    }

    // relative to dir, forward slashed
    private List<String> listFilePaths(Path dir) throws IOException {
        return Files.find(tempDir, 10, (p, a) -> Files.isRegularFile(p))
                .map(p -> dir.relativize(p))
                .map(Path::toString)
                .map(n -> n.replace('\\', '/'))
                .toList();
    }

    private Path savedFile(DocHandlerContext doc) {
        return Path.of(doc.metadata().getString(FLD_PATH));
    }

    private DocHandlerContext transform(SaveDocumentTransformer t, String ref)
            throws IOException {
        var is = TestUtil.toInputStream("blah");
        var docCtx = TestUtil.newHandlerContext(ref, is);
        t.handle(docCtx);
        return docCtx;
    }

    @Test
    void testWriteRead() {
        var t = new SaveDocumentTransformer();
        t.getConfiguration()
                .setDefaultFileName("index.html")
                .setDirSplitPattern("__")
                .setEscape(true)
                .setMaxPathLength(255)
                .setPathToField("myfield")
                .setSaveDir(Path.of("/somewhere/"));
        assertThatNoException().isThrownBy(
                () -> BeanMapper.DEFAULT.assertWriteRead(t));
    }
}
