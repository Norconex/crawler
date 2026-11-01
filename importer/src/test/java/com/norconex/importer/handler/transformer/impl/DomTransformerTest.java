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

import static com.norconex.commons.lang.map.PropertySetter.APPEND;
import static com.norconex.commons.lang.map.PropertySetter.REPLACE;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.NullInputStream;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.ResourceLoader;
import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.bean.BeanMapper.Format;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.TestUtil;
import com.norconex.importer.doc.DocMetaConstants;
import com.norconex.importer.util.DomUtil;

@Timeout(30)
@SuppressWarnings("null")
class DomTransformerTest {

    //--- Field tests ----------------------------------------------------------

    @Test
    void testFieldDelete() throws IOException {

        final var child1 = """
                <div id="childOneId" class="childClass">\
                <a href="http://example.org/doc.html">\
                Child1 Link</a></div>""";
        final var child2 = """
                <div class="childClass">Child2 text</div>""";

        final var full = """
                <div id="parentId" class="parentClass">%s%s</div>"""
                .formatted(child1, child2);

        final var fullMinusChild1 = """
                <div id="parentId" class="parentClass">%s</div>"""
                .formatted(child2);

        var metadata = new Properties();
        metadata.set("fromField1", full);

        var t = new DomTransformer();
        t.getConfiguration()
                .setParser(DomUtil.PARSER_XML)
                .setFieldMatcher(TextMatcher.basic("fromField1"))
                .setOperations(
                        List.of(
                                new DomOperation()
                                        .setDelete(true)
                                        .setSelector("#childOneId")
                                        .setExtract("outerHtml")
                                        .setToField("toField1")));

        metadata.set(DocMetaConstants.CONTENT_TYPE, "text/html");
        InputStream content = new NullInputStream(0);
        t.handle(TestUtil.newHandlerContext("n/a", content, metadata));

        var fromXml = metadata.getString("fromField1");
        var toXml = metadata.getString("toField1");

        Assertions.assertEquals(child1, cleanHTML(toXml));
        Assertions.assertEquals(fullMinusChild1, cleanHTML(fromXml));

    }

    @Test
    void testFieldNestedDelete() throws IOException {

        var child1 = """
                <div id="childOneId" class="childClass">\
                <a href="http://example.org/doc.html">\
                Child1 Link</a></div>""";
        var child2 = "<div class=\"childClass\">Child2 text</div>";

        var full = "<div id=\"parentId\" class=\"parentClass\">"
                + child1 + child2 + "</div>";

        var metadata = new Properties();
        metadata.set("fromField1", full);

        var t = new DomTransformer();
        t.getConfiguration()
                .setParser(DomUtil.PARSER_XML)
                .setFieldMatcher(TextMatcher.basic("fromField1"))
                .setOperations(
                        List.of(
                                new DomOperation()
                                        .setDelete(true)
                                        .setSelector("div")));

        metadata.set(DocMetaConstants.CONTENT_TYPE, "text/html");
        InputStream content = new NullInputStream(0);
        t.handle(TestUtil.newHandlerContext("n/a", content, metadata));

        var fromXml = metadata.getString("fromField1");

        Assertions.assertEquals("", cleanHTML(fromXml));

    }

    // This is a test for: https://github.com/Norconex/collector-http/issues/381
    @Test
    void testFieldXMLParser() throws IOException {

        var t = new DomTransformer();
        t.getConfiguration()
                .setParser(DomUtil.PARSER_XML)
                .setOperations(
                        List.of(
                                new DomOperation()
                                        .setSelector("tr")
                                        .setToField("WHOLE")
                                        .setOnSet(REPLACE)
                                        .setExtract("outerHtml"),
                                new DomOperation()
                                        .setSelector("tr td:nth-of-type(1) a")
                                        .setToField("TEST_URL")
                                        .setOnSet(REPLACE)
                                        .setExtract("attr(href)"),
                                new DomOperation()
                                        .setSelector("tr td:nth-of-type(1) a")
                                        .setToField("TEST_TITLE")
                                        .setOnSet(REPLACE)
                                        .setExtract("ownText"),
                                new DomOperation()
                                        .setSelector("tr td:nth-of-type(2)")
                                        .setToField("TEST_DESC")
                                        .setOnSet(REPLACE)
                                        .setExtract("ownText")));

        var xml = """
                <tr>\
                <td><a href="http://example.org/doc.html">\
                Sample Title</a></td>\
                <td>This is a description.</td>\
                </tr>""";

        var metadata = new Properties();
        performTransform(metadata, t, xml);

        var whole = metadata.getString("WHOLE");
        var title = metadata.getString("TEST_TITLE");
        var url = metadata.getString("TEST_URL");
        var desc = metadata.getString("TEST_DESC");

        Assertions.assertTrue(Strings.CI.contains(whole, "</tr>"));
        Assertions.assertEquals("Sample Title", title);
        Assertions.assertEquals("http://example.org/doc.html", url);
        Assertions.assertEquals("This is a description.", desc);
    }

    // This is a test for: https://github.com/Norconex/importer/issues/39
    @Test
    void testFieldMatchBlanks() throws IOException {

        var t = new DomTransformer();
        t.getConfiguration().setOperations(
                List.of(
                        new DomOperation()
                                .setSelector("author name")
                                .setToField("blanksON")
                                .setOnSet(APPEND)
                                .setMatchBlanks(true),
                        new DomOperation()
                                .setSelector("author name")
                                .setToField("blanksOFF")
                                .setOnSet(APPEND)
                                .setMatchBlanks(false),
                        new DomOperation()
                                .setSelector("author name")
                                .setToField("blanksONDefault")
                                .setOnSet(APPEND)
                                .setMatchBlanks(true)
                                .setDefaultValue("Joe"),
                        new DomOperation()
                                .setSelector("author name")
                                .setToField("blanksOFFDefault")
                                .setOnSet(APPEND)
                                .setMatchBlanks(false)
                                .setDefaultValue("Joe")));

        var xml = """
                <test>\
                <author><name>John</name></author>\
                <author><name></name></author>\
                <author><name>   </name></author>\
                <author></author>\
                </test>""";

        var metadata = new Properties();
        performTransform(metadata, t, xml);

        var blanksON = getSortedArray(metadata, "blanksON");
        var blanksOFF = getSortedArray(metadata, "blanksOFF");
        var blanksONDefault = getSortedArray(metadata, "blanksONDefault");
        var blanksOFFDefault =
                getSortedArray(metadata, "blanksOFFDefault");

        Assertions.assertArrayEquals(new String[] { "", "", "John" }, blanksON);
        Assertions.assertArrayEquals(new String[] { "John" }, blanksOFF);

        Assertions.assertArrayEquals(
                new String[] { "", "", "John" }, blanksONDefault);
        Assertions.assertArrayEquals(
                new String[] { "Joe", "Joe", "John" }, blanksOFFDefault);
    }

    // This is a test for: https://github.com/Norconex/importer/issues/39
    // where default value should not be trimmed.
    @Test
    void testFieldDefaultValue()
            throws IOException {

        var cfg =
                """
                        <handler class="\
                          com.norconex.importer.handler.transformer.impl.DomTransformer">\
                          <operations>
                            <op selector="author name" toField="noDefault"/>\
                            <op selector="author name" toField="emptyDefault" \
                              defaultValue=""/>\
                            <op selector="author name" toField="spaceDefault" \
                              defaultValue="   "/>\
                          </operations>
                        </handler>""";
        var t = new DomTransformer();
        BeanMapper.DEFAULT.read(t, new StringReader(cfg), Format.XML);

        var xml = "<test><author><name></name></author></test>";

        var metadata = new Properties();
        performTransform(metadata, t, xml);

        var noDefault = metadata.getString("noDefault");
        var emptyDefault = metadata.getString("emptyDefault");
        var spaceDefault = metadata.getString("spaceDefault");

        Assertions.assertEquals(null, noDefault);
        Assertions.assertEquals("", emptyDefault);
        Assertions.assertEquals("   ", spaceDefault);
    }

    // This is a test for "fromField" and "defaultValue" feature request:
    // https://github.com/Norconex/importer/issues/28
    @Test
    void testFieldFromField() throws IOException {

        var html = """
                <html><body>\
                <whatever/>\
                <div class="contact">\
                  <div class="firstName">JoeFirstOnly</div>\
                </div>\
                <whatever class="contact"></whatever>\
                <div class="contact">\
                  <div class="firstName">John</div>\
                  <div class="lastName">Smith</div>\
                </div>\
                <whatever/>\
                <div class="contact">\
                  <div class="lastName">JackLastOnly</div>\
                </div>\
                <whatever/>\
                </body></html>""";

        var metadata = new Properties();

        var parentTransformer = new DomTransformer();
        parentTransformer.getConfiguration().setOperations(
                List.of(
                        new DomOperation()
                                .setSelector("div.contact")
                                .setToField("htmlContacts")
                                .setOnSet(APPEND)
                                .setExtract("html")));
        performTransform(metadata, parentTransformer, html);

        var childTransformer = new DomTransformer();
        childTransformer.getConfiguration()
                .setFieldMatcher(TextMatcher.basic("htmlContacts"))
                .setOperations(
                        List.of(
                                new DomOperation()
                                        .setSelector("div.firstName")
                                        .setToField("firstName")
                                        .setOnSet(APPEND)
                                        .setDefaultValue("NoFirstName"),

                                new DomOperation()
                                        .setSelector("div.lastName")
                                        .setToField("lastName")
                                        .setOnSet(APPEND)
                                        .setExtract("html")
                                        .setDefaultValue("NoLastName")));

        performTransform(metadata, childTransformer, html);

        var firstNames = metadata.getStrings("firstName");
        var lastNames = metadata.getStrings("lastName");

        Assertions.assertEquals(
                Arrays.asList(
                        "JoeFirstOnly", "John", "NoFirstName"),
                firstNames);
        Assertions.assertEquals(
                Arrays.asList(
                        "NoLastName", "Smith", "JackLastOnly"),
                lastNames);
    }

    // This is a test for: https://github.com/Norconex/importer/issues/21
    @Test
    void testFieldNotAllSelectorsMatching()
            throws IOException {

        var t = new DomTransformer();
        t.getConfiguration().setOperations(
                List.of(
                        new DomOperation()
                                .setSelector("div.class1")
                                .setToField("match1")
                                .setOnSet(APPEND),
                        new DomOperation()
                                .setSelector("div.classNoMatch")
                                .setToField("match2")
                                .setOnSet(APPEND),
                        new DomOperation()
                                .setSelector("div.class3")
                                .setToField("match3")
                                .setOnSet(APPEND)));

        var html = """
                <html><body>\
                <div class="class1">text1</div>\
                <div class="class2">text2</div>\
                <div class="class3">text3</div>\
                </body></html>""";

        var metadata = new Properties();
        performTransform(metadata, t, html);

        var match1 = metadata.getString("match1");
        var match2 = metadata.getString("match2");
        var match3 = metadata.getString("match3");

        Assertions.assertEquals("text1", match1);
        Assertions.assertEquals(null, match2);
        Assertions.assertEquals("text3", match3);
    }

    @Test
    void testFieldExtractFromDOM()
            throws IOException {
        var t = new DomTransformer();
        t.getConfiguration().setOperations(
                List.of(
                        new DomOperation()
                                .setSelector("h2")
                                .setToField("headings")
                                .setOnSet(APPEND),
                        new DomOperation()
                                .setSelector("a[href]")
                                .setToField("links")
                                .setOnSet(REPLACE)
                                .setExtract("html")));

        var htmlFile = TestUtil.getAliceHtmlFile();
        try (InputStream is =
                new BufferedInputStream(new FileInputStream(htmlFile))) {
            var metadata = new Properties();
            metadata.set(DocMetaConstants.CONTENT_TYPE, "text/html");
            t.handle(
                    TestUtil.newHandlerContext(
                            htmlFile.getAbsolutePath(), is, metadata));
            var headings = metadata.getStrings("headings");
            var links = metadata.getStrings("links");
            Assertions.assertEquals(2, headings.size(), "Wrong <h2> count.");
            Assertions.assertEquals(
                    4, links.size(),
                    "Wrong <img src=\"...\"> count.");
            Assertions.assertEquals(
                    "CHAPTER I", headings.get(0),
                    "Did not extract first heading");
            Assertions.assertEquals(
                    "Down the Rabbit-Hole", headings.get(1),
                    "Did not extract second heading");
        }
    }

    @Test
    void testFieldExtractionTypes()
            throws IOException {
        var t = new DomTransformer();
        t.getConfiguration().setOperations(
                List.of(
                        new DomOperation()
                                .setSelector("head")
                                .setToField("fhtml")
                                .setOnSet(APPEND)
                                .setExtract("html"),
                        new DomOperation()
                                .setSelector("head")
                                .setToField("fouter")
                                .setOnSet(APPEND)
                                .setExtract("outerhtml"),
                        new DomOperation()
                                .setSelector("head")
                                .setToField("ftext")
                                .setOnSet(APPEND)
                                .setExtract("text")));

        var htmlFile = TestUtil.getAliceHtmlFile();
        try (InputStream is =
                new BufferedInputStream(new FileInputStream(htmlFile))) {
            var metadata = new Properties();
            metadata.set(DocMetaConstants.CONTENT_TYPE, "text/html");
            t.handle(
                    TestUtil.newHandlerContext(
                            htmlFile.getAbsolutePath(), is, metadata));

            var expectedText = "Alice's Adventures in Wonderland -- Chapter I";
            var expectedHtml = "<meta http-equiv=\"content-type\" "
                    + "content=\"text/html; charset=ISO-8859-1\">"
                    + "<title>" + expectedText + "</title>";
            var expectedOuter = "<head>" + expectedHtml + "</head>";

            Assertions.assertEquals(expectedText, metadata.getString("ftext"));
            Assertions.assertEquals(
                    expectedHtml,
                    cleanHTML(metadata.getString("fhtml")));
            Assertions.assertEquals(
                    expectedOuter,
                    cleanHTML(metadata.getString("fouter")));
        }
    }

    @Test
    void testFieldAllExtractionTypes()
            throws IOException {

        var t = new DomTransformer();
        t.getConfiguration().setOperations(
                List.of(
                        domOperation("div.parent", "text", APPEND, "text"),
                        domOperation("span.child1", "html", APPEND, "html"),
                        domOperation(
                                "span.child1", "outerHtml", APPEND,
                                "outerHtml"),
                        domOperation("script", "data", APPEND, "data"),
                        domOperation("div.parent", "id", APPEND, "id"),
                        domOperation(
                                "div.parent", "ownText", APPEND, "ownText"),
                        domOperation(
                                "div.parent", "tagName", APPEND, "tagName"),
                        domOperation(".formElement", "val", APPEND, "val"),
                        domOperation(
                                "textarea", "className", APPEND, "className"),
                        domOperation(
                                ".child2", "cssSelector", APPEND,
                                "cssSelector"),
                        domOperation("textarea", "attr", APPEND,
                                "attr(title)")));

        var content = """
                <html><body>\
                <script>This is data, not HTML.</script>\
                <div id="content" class="parent">Parent text.\
                <span class="child1">Child text <b>1</b>.</span>\
                <span class="child2">Child text <b>2</b>.</span>\
                </div>\
                <textarea class="formElement" title="Some Title">\
                textarea value.</textarea>\
                </body></html>""";

        var metadata = new Properties();
        try (InputStream is = new ByteArrayInputStream(content.getBytes())) {
            metadata.set(DocMetaConstants.CONTENT_TYPE, "text/html");

            t.handle(TestUtil.newHandlerContext("n/a", is, metadata));

            var text = metadata.getString("text");
            var html = metadata.getString("html");
            var outerHtml = metadata.getString("outerHtml");
            var data = metadata.getString("data");
            var id = metadata.getString("id");
            var ownText = metadata.getString("ownText");
            var tagName = metadata.getString("tagName");
            var val = metadata.getString("val");
            var className = metadata.getString("className");
            var cssSelector = metadata.getString("cssSelector");
            var attr = metadata.getString("attr");

            Assertions.assertEquals(
                    "Parent text.Child text 1.Child text 2.",
                    text);
            Assertions.assertEquals("Child text <b>1</b>.", html);
            Assertions.assertEquals(
                    "<span class=\"child1\">Child text <b>1</b>.</span>",
                    outerHtml);
            Assertions.assertEquals("This is data, not HTML.", data);
            Assertions.assertEquals("content", id);
            Assertions.assertEquals("Parent text.", ownText);
            Assertions.assertEquals("div", tagName);
            Assertions.assertEquals("textarea value.", val);
            Assertions.assertEquals("formElement", className);
            Assertions.assertEquals("#content > span.child2", cssSelector);
            Assertions.assertEquals("Some Title", attr);
        }
    }

    @Test
    void testFieldWriteRead() {
        var t = new DomTransformer();
        t.getConfiguration()
                .setOperations(
                        List.of(
                                domOperation(
                                        "p.blah > a", "myField", REPLACE,
                                        "text"),
                                domOperation(
                                        "div.blah > a", "myOtherField", REPLACE,
                                        "html")
                                                .setDefaultValue(
                                                        "myDefaultValue")
                                                .setDelete(true)))
                .setFieldMatcher(TextMatcher.basic("myfromfield"));
        BeanMapper.DEFAULT.assertWriteRead(t);
    }

    //--- Body preserve tests --------------------------------------------------

    @Test
    void testPreserveWriteRead() {
        var oper1 = new DomOperation()
                .setSelector("someTag")
                .setExtract("text");
        var oper2 = new DomOperation()
                .setSelector("otherTag")
                .setExtract("html");

        var t = new DomTransformer();
        t.getConfiguration()
                .setParser("xml")
                .setOperations(List.of(oper1, oper2))
                .setSourceCharset(StandardCharsets.ISO_8859_1);

        Assertions.assertEquals(2, t.getConfiguration().getOperations().size());
        Assertions.assertNotSame(oper1, oper2);
        Assertions.assertNotEquals(oper1.toString(), oper2.toString());
        Assertions.assertDoesNotThrow(
                () -> BeanMapper.DEFAULT.assertWriteRead(t));
    }

    @Test
    void testPreserveTransform() throws IOException {
        var t = new DomTransformer();

        // Test batch #1
        t.getConfiguration()
                .setParser("xml")
                .setOperations(
                        List.of(
                                new DomOperation() // preserve: tag text
                                        .setSelector("parentA > childA1")
                                        .setExtract("text"),
                                new DomOperation() // preserve: attribute
                                        .setSelector("parentB > childB1")
                                        .setExtract("attr(name)"),
                                new DomOperation() // no match: use default
                                        .setSelector("parentD > childD1")
                                        .setDefaultValue("Child D1"),
                                new DomOperation() // no match: no default
                                        .setSelector("parentE > childE1")));

        Assertions.assertEquals(
                "Child A1\nchild1\nChild D1", transformPreserve(t));

        // Test batch #2
        t.getConfiguration()
                .setOperations(
                        List.of(
                                new DomOperation() // preserve: tag html
                                        .setSelector("childA2")
                                        .setExtract("html"),
                                new DomOperation() // preserve: tag outerHtml
                                        .setSelector("childA2")
                                        .setExtract("outerHtml")));

        Assertions.assertEquals(
                "<extra>Child A2</extra>\n"
                        + "<childA2><extra>Child A2</extra></childA2>",
                transformPreserve(t));

        // Test batch #3
        t.getConfiguration()
                .setOperations(
                        List.of(
                                new DomOperation() // no match: ownText
                                        .setSelector("parentA")
                                        .setExtract("ownText"),
                                new DomOperation() // preserve: data
                                        .setSelector("parentB")
                                        .setExtract("data"),
                                new DomOperation() // preserve: ownText
                                        .setSelector("parentC")
                                        .setExtract("ownText")));
        Assertions.assertEquals(
                "I'm Data\nParent C Before Parent C After",
                transformPreserve(t));

        // Test batch #4
        t.getConfiguration()
                .setOperations(
                        List.of(
                                new DomOperation() // preserve: tagName
                                        .setSelector("[name=child1]")
                                        .setExtract("tagName"),
                                new DomOperation() // preserve: cssSelector
                                        .setSelector("childC")
                                        .setExtract("cssSelector")));
        Assertions.assertEquals(
                """
                        childB1
                        childC
                        DomTransformerTest > parentC > childC:nth-child(1)
                        DomTransformerTest > parentC > childC:nth-child(2)""",
                transformPreserve(t));
    }

    //--- Body delete tests ----------------------------------------------------

    @Test
    void testBodyDelete() throws IOException {

        var child1 = """
                <div id="childOneId" class="childClass">\
                <a href="http://example.org/doc.html">\
                Child1 Link</a></div>""";
        var child2 = "<div class=\"childClass\">Child2 text</div>";

        var full = "<div id=\"parentId\" class=\"parentClass\">"
                + child1 + child2 + "</div>";

        var fullMinusChild1 = "<div id=\"parentId\" class=\"parentClass\">"
                + child2 + "</div>";

        var t = new DomTransformer();
        t.getConfiguration()
                .setParser(DomUtil.PARSER_XML)
                .setSourceCharset(UTF_8)
                .setOperations(
                        List.of(
                                new DomOperation()
                                        .setSelector("#childOneId")
                                        .setDelete(true)));

        var metadata = new Properties();
        metadata.set(DocMetaConstants.CONTENT_TYPE, "text/html");

        var content = IOUtils.toInputStream(full, UTF_8);
        var doc = TestUtil.newHandlerContext("n/a", content, metadata);
        t.handle(doc);

        var output = doc.input().asString();
        content.close();
        Assertions.assertEquals(fullMinusChild1, cleanHTML(output));
    }

    @Test
    void testBodyNestedDelete()
            throws IOException {

        var child1 = """
                <div id="childOneId" class="childClass">\
                <a href="http://example.org/doc.html">\
                Child1 Link</a></div>""";
        var child2 = "<div class=\"childClass\">Child2 text</div>";

        var full = "<div id=\"parentId\" class=\"parentClass\">"
                + child1 + child2 + "</div>";

        var t = new DomTransformer();
        t.getConfiguration()
                .setParser(DomUtil.PARSER_XML)
                .setSourceCharset(UTF_8)
                .setOperations(
                        List.of(
                                new DomOperation()
                                        .setSelector("div")
                                        .setDelete(true)));

        var metadata = new Properties();
        metadata.set(DocMetaConstants.CONTENT_TYPE, "text/html");
        var content = IOUtils.toInputStream(full, UTF_8);
        var doc = TestUtil.newHandlerContext("n/a", content, metadata);
        t.handle(doc);
        var output = doc.input().asString();
        content.close();
        Assertions.assertEquals("", cleanHTML(output));
    }

    @Test
    void testBodyDeleteWriteRead() {
        var t = new DomTransformer();
        t.getConfiguration()
                .setParser("xml")
                .setSourceCharset(UTF_8)
                .setOperations(
                        List.of(
                                new DomOperation()
                                        .setSelector("p.blah > a")
                                        .setDelete(true)));
        BeanMapper.DEFAULT.assertWriteRead(t);
    }

    //--- Private methods ------------------------------------------------------

    private static String transformPreserve(DomTransformer t)
            throws IOException {
        try (var content =
                ResourceLoader.getXmlStream(DomTransformerTest.class);
                var os = new ByteArrayOutputStream()) {
            var metadata = new Properties();
            metadata.set(DocMetaConstants.CONTENT_TYPE, "application/xml");

            var doc = TestUtil.newHandlerContext("n/a", content, metadata);
            t.handle(doc);
            return doc.input().asString();
        }
    }

    private DomOperation domOperation(
            String selector,
            String toField,
            PropertySetter setter,
            String extract) {
        return new DomOperation()
                .setSelector(selector)
                .setToField(toField)
                .setOnSet(setter)
                .setExtract(extract);
    }

    private String[] getSortedArray(Properties metadata, String key) {
        var list = metadata.getStrings(key);
        Collections.sort(list);
        return list.toArray(ArrayUtils.EMPTY_STRING_ARRAY);
    }

    private void performTransform(
            Properties metadata, DomTransformer t, String html)
            throws IOException {
        try (InputStream is = new ByteArrayInputStream(html.getBytes())) {
            metadata.set(DocMetaConstants.CONTENT_TYPE, "text/html");
            t.handle(TestUtil.newHandlerContext("n/a", is, metadata));
        }
    }

    private String cleanHTML(String html) {
        var clean = html;
        clean = clean.replaceAll("[\\r\\n]", "");
        clean = clean.replaceAll(">\\s+", ">");
        return clean.replaceAll("\\s+<", "<");
    }
}
