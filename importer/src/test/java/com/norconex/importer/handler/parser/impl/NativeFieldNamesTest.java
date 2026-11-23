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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.commons.lang.map.Properties;
import com.norconex.importer.Importer;
import com.norconex.importer.ImporterConfig;
import com.norconex.importer.ImporterRequest;

/**
 * Fields of a document come out under the names its author gave them, not
 * under the names the parser would have given them.
 */
@Timeout(30)
class NativeFieldNamesTest {

    @TempDir
    static Path folder;

    private static final String PAGE = """
            <!DOCTYPE html><html lang="en"><head>
            <title>Element Title</title>
            <meta name="title" content="Meta Title">
            <meta name="dc:title" content="DC Title">
            <meta name="DC.Title" content="DC Dot Title">
            <meta name="Author" content="Jane Author">
            <meta name="description" content="Meta description">
            <meta name="keywords" content="k1, k2">
            <meta name="subject" content="Meta subject">
            <meta name="generator" content="Gen 1.0">
            <meta name="ICBM" content="45.5, -73.6">
            <meta http-equiv="Last-Modified" content="Tue, 01 Jan 2026 00:00:00 GMT">
            <meta property="og:title" content="OG Title">
            <meta name="empty" content="">
            </head><body><p>Hello</p>
            <meta name="inbody" content="ignored">
            </body></html>""";

    private static Properties importHtml(String html) throws IOException {
        var file = Files.createTempFile(folder, "page", ".html");
        Files.writeString(file, html);
        try (var importer = new Importer(new ImporterConfig())) {
            var response = importer.importDocument(new ImporterRequest(file));
            assertThat(response.getDoc()).isNotNull();
            return response.getDoc().getMetadata();
        }
    }

    @Test
    void htmlMetaTagsKeepTheNamesThePageGaveThem() throws IOException {
        var md = importHtml(PAGE);

        // each name as published, case included, with its own value
        // the <title> element is named for its tag, and comes first
        assertThat(md.getStrings("title"))
                .containsExactly("Element Title", "Meta Title");
        assertThat(md.getStrings("dc:title")).containsExactly("DC Title");
        assertThat(md.getStrings("DC.Title")).containsExactly("DC Dot Title");
        assertThat(md.getStrings("Author")).containsExactly("Jane Author");
        assertThat(md.getStrings("description"))
                .containsExactly("Meta description");
        assertThat(md.getStrings("keywords")).containsExactly("k1, k2");
        assertThat(md.getStrings("subject")).containsExactly("Meta subject");
        assertThat(md.getStrings("generator")).containsExactly("Gen 1.0");
        assertThat(md.getStrings("ICBM")).containsExactly("45.5, -73.6");
        assertThat(md.getStrings("Last-Modified"))
                .containsExactly("Tue, 01 Jan 2026 00:00:00 GMT");
        assertThat(md.getStrings("og:title")).containsExactly("OG Title");

        // blank values and tags outside the head are not fields
        assertThat(md.containsKey("empty")).isFalse();
        assertThat(md.containsKey("inbody")).isFalse();
    }

    @Test
    void htmlParserNamesDoNotStandForWhatThePageSaid() throws IOException {
        var md = importHtml(PAGE);

        // the parser's prefix is gone, and so are the names it worked out
        assertThat(md.keySet()).noneMatch(k -> k.startsWith("html:"));
        assertThat(md.containsKey("dc:creator")).isFalse();
        assertThat(md.containsKey("dc:description")).isFalse();
        assertThat(md.containsKey("meta:keyword")).isFalse();
        assertThat(md.containsKey("geo:lat")).isFalse();

        // nothing is left under a name only the parser would have chosen for
        // the title, whether it came from the element or from a tag
        assertThat(md.getStrings("dc:title")).containsExactly("DC Title");
    }

    @Test
    void titleElementComesFirstWhateverTheOrderOfTags() throws IOException {
        var before = importHtml(
                "<html><head><title>T</title>"
                        + "<meta name=\"title\" content=\"M\"></head>"
                        + "<body>x</body></html>");
        var after = importHtml(
                "<html><head><meta name=\"title\" content=\"M\">"
                        + "<title>T</title></head><body>x</body></html>");
        assertThat(before.getStrings("title")).containsExactly("T", "M");
        assertThat(after.getStrings("title")).containsExactly("T", "M");
    }

    @Test
    void titleElementIsKeptWhenATagSaysTheSame() throws IOException {
        var md = importHtml(
                "<html><head><title>Same</title>"
                        + "<meta name=\"title\" content=\"Same\"></head>"
                        + "<body>x</body></html>");
        assertThat(md.getStrings("title")).containsExactly("Same");
    }

    @Test
    void pageWithoutTitleElementHasOnlyItsTags() throws IOException {
        var md = importHtml(
                "<html><head><meta name=\"title\" content=\"M1\">"
                        + "<meta name=\"dc:title\" content=\"M2\"></head>"
                        + "<body>x</body></html>");
        assertThat(md.getStrings("title")).containsExactly("M1");
        assertThat(md.getStrings("dc:title")).containsExactly("M2");
    }

    @Test
    void onlyTheFirstTitleElementCountsAndItIsTrimmed() throws IOException {
        var md = importHtml(
                "<html><head><title>\n   First   one \n</title>"
                        + "<title>Second</title></head>"
                        + "<body>x</body></html>");
        assertThat(md.getStrings("title")).containsExactly("First   one");
    }

    @Test
    void dcNamesAuthoredInThePageStayAsAuthoredAndAreNotCopied()
            throws IOException {
        var md = importHtml(
                "<html><head><meta name=\"dc:creator\" content=\"Jane\">"
                        + "<meta name=\"dc:description\" content=\"Desc\">"
                        + "<meta name=\"dc:subject\" content=\"Subj\">"
                        + "</head><body>x</body></html>");
        assertThat(md.getStrings("dc:creator")).containsExactly("Jane");
        assertThat(md.getStrings("dc:description")).containsExactly("Desc");
        assertThat(md.getStrings("dc:subject")).containsExactly("Subj");
        // no bare copies under names the page never used
        assertThat(md.containsKey("creator")).isFalse();
        assertThat(md.containsKey("description")).isFalse();
        assertThat(md.containsKey("subject")).isFalse();
    }
}
