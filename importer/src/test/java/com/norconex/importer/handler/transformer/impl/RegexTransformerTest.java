/* Copyright 2020-2026 Norconex Inc.
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

import static org.assertj.core.api.Assertions.assertThatNoException;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.commons.lang.text.RegexFieldValueExtractor;
import com.norconex.importer.TestUtil;
import com.norconex.importer.doc.DocMetaConstants;
import com.norconex.importer.handler.parser.ParseState;

@Timeout(30)
@SuppressWarnings("null")
class RegexTransformerTest {

    @Test
    void testTagTextDocument()
            throws IOException, IOException {
        var t = new RegexTransformer();
        t.getConfiguration().setPatterns(
                List.of(
                        new RegexFieldValueExtractor(
                                "<h2>(.*?)</h2>", "headings", 1),
                        new RegexFieldValueExtractor(
                                "\\w+\\sZealand", "country")));
        var htmlFile = TestUtil.getAliceHtmlFile();
        InputStream is = new BufferedInputStream(new FileInputStream(htmlFile));

        var metadata = new Properties();
        metadata.set(DocMetaConstants.CONTENT_TYPE, "text/html");
        t.handle(
                TestUtil.newHandlerContext(
                        htmlFile.getAbsolutePath(), is, metadata,
                        ParseState.PRE));

        is.close();

        var headings = metadata.getStrings("headings");
        var countries = metadata.getStrings("country");

        Assertions.assertEquals(2, headings.size(), "Wrong <h2> count.");
        Assertions.assertEquals(
                "CHAPTER I", headings.get(0),
                "Did not extract first heading");
        Assertions.assertEquals(
                "Down the Rabbit-Hole", headings.get(1),
                "Did not extract second heading");

        Assertions.assertEquals(1, countries.size(), "Wrong country count.");
        Assertions.assertEquals(
                "New Zealand", countries.get(0),
                "Did not extract country");
    }

    @Test
    void testExtractFirst100ContentChars()
            throws IOException, IOException {
        var t = new RegexTransformer();
        t.getConfiguration().setPatterns(
                List.of(
                        new RegexFieldValueExtractor("^.{0,100}", "mytitle")));
        var htmlFile = TestUtil.getAliceHtmlFile();
        InputStream is = new BufferedInputStream(new FileInputStream(htmlFile));
        var metadata = new Properties();
        metadata.set(DocMetaConstants.CONTENT_TYPE, "text/html");
        t.handle(
                TestUtil.newHandlerContext(
                        htmlFile.getAbsolutePath(), is, metadata,
                        ParseState.PRE));

        is.close();

        var myTitle = metadata.getString("mytitle");
        Assertions.assertEquals(100, myTitle.length());
    }

    @Test
    void testWriteRead() {
        var t = new RegexTransformer();
        t.getConfiguration().setPatterns(
                List.of(
                        new RegexFieldValueExtractor("123.*890", "field1"),
                        new RegexFieldValueExtractor("abc.*xyz", "field2", 3),
                        new RegexFieldValueExtractor("blah")
                                .setToField("field3")
                                .setFieldGroup(3)
                                .setValueGroup(6)
                                .setOnSet(PropertySetter.PREPEND)

                ));
        t.getConfiguration().setMaxReadSize(512);
        assertThatNoException()
                .isThrownBy(() -> BeanMapper.DEFAULT.assertWriteRead(t));
    }
}
