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
package com.norconex.importer.handler.parser.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.TestUtil;
import com.norconex.importer.doc.DocMetaConstants;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.parser.ParseState;

@Timeout(60)
class ParserPoolIntegrationTest {
    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void testDefaultParserConcurrentDocuments(int size) throws Exception {
        var parser = new DefaultParser();
        parser.getConfiguration().setPoolSize(size);
        checkConcurrentHtml(parser);
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2 })
    void testCustomTikaParserConcurrentDocuments(int size, @TempDir Path folder)
            throws Exception {
        var config = folder.resolve("tika.xml");
        // A deliberately restricted parser graph proves the supplied config
        // is applied to every pool member.
        Files.writeString(config, """
                <properties><parsers>
                  <parser class="org.apache.tika.parser.html.HtmlParser"/>
                </parsers></properties>
                """);
        var parser = new TikaParser();
        parser.getConfiguration().setPoolSize(size)
                .setTikaConfigFile(config.toString());
        checkConcurrentHtml(parser);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void testEmbeddedZipWithOneParser(boolean split) throws Exception {
        var parser = new DefaultParser();
        parser.getConfiguration().setPoolSize(1);
        if (split) {
            parser.getConfiguration().getEmbeddedConfig()
                    .setSplitContentTypes(java.util.List.of(
                            TextMatcher.basic("application/zip")));
        }
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("child.txt"));
            zip.write("embedded-pool-content".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        var metadata = new Properties();
        metadata.set(DocMetaConstants.CONTENT_TYPE, "application/zip");
        var context = TestUtil.newHandlerContext("container.zip",
                new ByteArrayInputStream(bytes.toByteArray()), metadata);
        parser.init();
        try {
            assertThat(parser.handle(context)).isTrue();
            assertThat(context.parseState()).isEqualTo(ParseState.POST);
            if (split) {
                assertThat(context.childDocs()).hasSize(1);
                assertThat(TestUtil
                        .contentAsString(context.childDocs().getFirst()))
                                .contains("embedded-pool-content");
            } else {
                assertThat(context.input().asString())
                        .contains("embedded-pool-content");
            }
        } finally {
            parser.close();
        }
    }

    private void checkConcurrentHtml(DocHandler parser) throws Exception {
        parser.init();
        try (var executor = Executors.newFixedThreadPool(6)) {
            var results = new ArrayList<Future<?>>();
            for (int i = 0; i < 12; i++) {
                var id = i;
                results.add(executor.submit(() -> {
                    var metadata = new Properties();
                    metadata.set(DocMetaConstants.CONTENT_TYPE, "text/html");
                    var html = "<html><head><title>title-" + id
                            + "</title></head><body>body-" + id
                            + "</body></html>";
                    var context =
                            TestUtil.newHandlerContext("page-" + id + ".html",
                                    TestUtil.toInputStream(html), metadata);
                    assertThat(parser.handle(context)).isTrue();
                    assertThat(context.input().asString())
                            .isEqualTo("body-" + id);
                    assertThat(context.metadata().getString("dc:title"))
                            .isEqualTo("title-" + id);
                    assertThat(context.parseState()).isEqualTo(ParseState.POST);
                    return null;
                }));
            }
            for (var result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            parser.close();
        }
    }
}
