/* Copyright 2014-2026 Norconex Inc.
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

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.apache.commons.io.input.NullInputStream;
import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.importer.TestUtil;
import com.norconex.importer.handler.parser.ParseState;

@Timeout(30)
@SuppressWarnings("null")
class HierarchyTransformerTest {

    @Test
    void testWriteRead() {
        var t = new HierarchyTransformer();
        t.getConfiguration().setOperations(
                List.of(
                        new HierarchyOperation()
                                .setFromField("fromField1")
                                .setToField("toField1")
                                .setFromSeparator("fromSep1")
                                .setToSeparator("toSep1"),
                        new HierarchyOperation()
                                .setFromField("fromField2")
                                .setFromSeparator("fromSep2"),
                        new HierarchyOperation()
                                .setToField("toField3")
                                .setToSeparator("toSep3"),
                        new HierarchyOperation()
                                .setFromField("fromField4")
                                .setToField("toField4")
                                .setFromSeparator("fromSep4")
                                .setToSeparator("toSep4")
                                .setOnSet(PropertySetter.REPLACE)));

        assertThatNoException().isThrownBy(
                () -> BeanMapper.DEFAULT.assertWriteRead(t));
    }

    @Test
    void testDiffToAndFromSeparators() throws IOException {
        tagAndAssert(
                "/", "~~~",
                "~~~vegetable",
                "~~~vegetable~~~potato",
                "~~~vegetable~~~potato~~~sweet");
    }

    @Test
    void testSameOrNoToSeparators() throws IOException {
        tagAndAssert(
                "/", "/",
                "/vegetable", "/vegetable/potato", "/vegetable/potato/sweet");
        tagAndAssert(
                "/", null,
                "/vegetable", "/vegetable/potato", "/vegetable/potato/sweet");
    }

    @Test
    void testMultiCharSeparator() throws IOException {
        var meta = createDefaultTestMetadata(
                "//vegetable//potato//sweet");
        var t = createDefaultTagger("//", "!");
        Assertions.assertArrayEquals(
                new String[] {
                        "!vegetable",
                        "!vegetable!potato",
                        "!vegetable!potato!sweet"
                }, tag(t, meta));
    }

    @Test
    void testEmptySegments() throws IOException {
        var meta = createDefaultTestMetadata(
                "//vegetable/potato//sweet/fries//");
        var t = createDefaultTagger("/", "!");

        // do not keep empty segments
        Assertions.assertArrayEquals(
                new String[] {
                        "!vegetable",
                        "!vegetable!potato",
                        "!vegetable!potato!sweet",
                        "!vegetable!potato!sweet!fries"
                }, tag(t, meta));

        // keep empty segments
        meta.remove("targetField");
        t.getConfiguration().getOperations().get(0).setKeepEmptySegments(true);
        Assertions.assertArrayEquals(
                new String[] {
                        "!",
                        "!!vegetable",
                        "!!vegetable!potato",
                        "!!vegetable!potato!",
                        "!!vegetable!potato!!sweet",
                        "!!vegetable!potato!!sweet!fries",
                        "!!vegetable!potato!!sweet!fries!"
                }, tag(t, meta));
    }

    @Test
    void testMultipleWithMiscSeparatorPlacement() throws IOException {
        var meta = createDefaultTestMetadata(
                "/vegetable/potato/sweet",
                "vegetable/potato/sweet",
                "vegetable/potato/sweet/",
                "/vegetable/potato/sweet/");
        var t = createDefaultTagger("/", "|");
        Assertions.assertArrayEquals(
                new String[] {
                        "|vegetable",
                        "|vegetable|potato",
                        "|vegetable|potato|sweet",

                        "vegetable",
                        "vegetable|potato",
                        "vegetable|potato|sweet",

                        "vegetable",
                        "vegetable|potato",
                        "vegetable|potato|sweet",

                        "|vegetable",
                        "|vegetable|potato",
                        "|vegetable|potato|sweet"
                }, tag(t, meta));
    }

    @Test
    void testRegexSeparatorWithToSep() throws IOException {
        var meta = createDefaultTestMetadata(
                "/1/vegetable/2/potato/3//4/sweet");
        var t = createDefaultTagger("/\\d/", "!");
        t.getConfiguration().getOperations().get(0).setRegex(true);
        Assertions.assertArrayEquals(
                new String[] {
                        "!vegetable",
                        "!vegetable!potato",
                        "!vegetable!potato!sweet"
                }, tag(t, meta));
    }

    @Test
    void testRegexSeparatorWithToSepKeepEmpty() throws IOException {
        var meta = createDefaultTestMetadata(
                "/1/vegetable/2/potato/3//4/sweet");
        var t = createDefaultTagger("/\\d/", "!");
        t.getConfiguration().getOperations().get(0)
                .setKeepEmptySegments(true)
                .setRegex(true);
        Assertions.assertArrayEquals(
                new String[] {
                        "!vegetable",
                        "!vegetable!potato",
                        "!vegetable!potato!",
                        "!vegetable!potato!!sweet"
                }, tag(t, meta));
    }

    @Test
    void testRegexSeparatorWithoutToSep() throws IOException {
        var meta = createDefaultTestMetadata(
                "/1/vegetable/2/potato/3//4/sweet");
        var t = createDefaultTagger("/\\d/", null);
        t.getConfiguration().getOperations().get(0).setRegex(true);
        Assertions.assertArrayEquals(
                new String[] {
                        "/1/vegetable",
                        "/1/vegetable/2/potato",
                        "/1/vegetable/2/potato/4/sweet"
                }, tag(t, meta));
    }

    @Test
    void testRegexSeparatorWithoutToSepKeepEmpty() throws IOException {
        var meta = createDefaultTestMetadata(
                "/1/vegetable/2/potato/3//4/sweet");
        var t = createDefaultTagger("/\\d/", null);
        t.getConfiguration().getOperations().get(0)
                .setKeepEmptySegments(true)
                .setRegex(true);
        Assertions.assertArrayEquals(
                new String[] {
                        "/1/vegetable",
                        "/1/vegetable/2/potato",
                        "/1/vegetable/2/potato/3/",
                        "/1/vegetable/2/potato/3//4/sweet"
                }, tag(t, meta));
    }

    private void tagAndAssert(
            String fromSep, String toSep, String... expected)
            throws IOException {
        var meta = createDefaultTestMetadata();
        var t = createDefaultTagger(fromSep, toSep);
        Assertions.assertArrayEquals(expected, tag(t, meta));
    }

    private String[] tag(HierarchyTransformer t, Properties meta)
            throws IOException {
        InputStream is = new NullInputStream(0);
        t.handle(
                TestUtil.newHandlerContext(
                        "blah", is, meta, ParseState.PRE));
        return meta.getStrings("targetField").toArray(new String[] {});
    }

    private HierarchyTransformer createDefaultTagger(
            String fromSep, String toSep) {
        var t = new HierarchyTransformer();
        t.getConfiguration().setOperations(
                List.of(
                        new HierarchyOperation()
                                .setFromField("sourceField")
                                .setToField("targetField")
                                .setFromSeparator(fromSep)
                                .setToSeparator(toSep)));
        return t;
    }

    private Properties createDefaultTestMetadata(String... testValues) {
        var meta = new Properties();
        if (ArrayUtils.isEmpty(testValues)) {
            meta.set("sourceField", "/vegetable/potato/sweet");
        } else {
            for (String value : testValues) {
                meta.add("sourceField", value);
            }
        }
        return meta;
    }

    //TODO test regex separator
}
