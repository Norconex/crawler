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
package com.norconex.importer.handler.parser.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.importer.Importer;
import com.norconex.importer.ImporterConfig;
import com.norconex.importer.ImporterRequest;

/**
 * Goes over the sample files of the formats whose fields are read as
 * their authors named them, and fails if a name only the parser would
 * have chosen is still there. It also prints, for each file, the fields
 * that are neither read from the file nor the parser's own ({@code tk:}),
 * to see at a glance what is still named by the parser.
 */
@Timeout(300)
class NativeFieldCoverageTest {

    private static final List<String> FORMATS =
            List.of("image", "msoffice", "pdf");

    /** Names the parsers give to what a file says under another name. */
    private static final List<String> PARSER_NAMES = List.of(
            "html:", "message:", "pdf:docinfo:", "xmp-raw:",
            "extended-properties:", "img:", "tiff:",
            "meta:last-author", "meta:page-count", "meta:word-count",
            "meta:character-count", "meta:paragraph-count",
            "meta:slide-count", "meta:line-count");

    @Test
    void noParserNameStandsInForWhatAFileSaid() throws IOException,
            URISyntaxException {
        var root = Paths.get(getClass().getResource("/parser").toURI());
        var report = new StringBuilder();
        var offenders = new ArrayList<String>();
        try (var importer = new Importer(new ImporterConfig())) {
            for (var format : FORMATS) {
                try (var files = Files.list(root.resolve(format))) {
                    for (var file : files.sorted().toList()) {
                        check(importer, file, report, offenders);
                    }
                }
            }
        }
        System.out.println(report);
        assertThat(offenders).isEmpty();
    }

    private static void check(
            Importer importer, Path file, StringBuilder report,
            List<String> offenders) {
        try {
            var response = importer.importDocument(new ImporterRequest(file));
            if (response.getDoc() == null) {
                return;
            }
            var keys = new TreeSet<>(response.getDoc().getMetadata().keySet());
            var leftover = new TreeSet<String>();
            for (var key : keys) {
                // JBIG2 pictures are read by the parser alone: its own names
                var parserAlone = file.getFileName().toString().contains("jb");
                if (!(parserAlone && (key.startsWith("img:")
                        || key.startsWith("tiff:")))
                        && PARSER_NAMES.stream().anyMatch(key::startsWith)) {
                    offenders.add(file.getFileName() + " -> " + key);
                }
                if (!key.startsWith("tk:") && !key.startsWith("document.")
                        && !key.startsWith("collector.")) {
                    leftover.add(key);
                }
            }
            report.append("\n## ").append(file.getFileName()).append(": ")
                    .append(String.join(", ", leftover));
        } catch (Exception e) {
            report.append("\n## ").append(file.getFileName())
                    .append(": not imported (").append(e).append(')');
        }
    }
}
