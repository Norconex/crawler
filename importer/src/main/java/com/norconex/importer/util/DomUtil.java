/* Copyright 2016-2024 Norconex Inc.
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
package com.norconex.importer.util;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility methods related to JSoup/DOM manipulation.
 */
public final class DomUtil {

    private static final Logger LOG = LoggerFactory.getLogger(DomUtil.class);

    /** @since 2.8.0 */
    public static final String PARSER_HTML = "html";
    /** @since 2.8.0 */
    public static final String PARSER_XML = "xml";

    private DomUtil() {
    }

    /**
     * Gets the JSoup parser associated with the string representation.
     * The string "xml" (case insensitive) will return the XML parser.
     * Anything else will return the HTML parser.
     * @param parser "html" or "xml"
     * @return JSoup parser
         */
    public static Parser toJSoupParser(String parser) {
        if (PARSER_XML.equalsIgnoreCase(parser)) {
            return Parser.xmlParser();
        }
        return Parser.htmlParser();
    }

    /**
     * <p>Gets an element value based on JSoup DOM.  You control what gets
     * extracted exactly thanks to the "extract" argument.
     * Possible values are:</p>
     * <ul>
     *   <li><b>text</b>: Default option when extract is blank. The text of
     *       the element, including combined children.</li>
     *   <li><b>html</b>: Extracts an element inner
     *       HTML (including children).</li>
     *   <li><b>outerHtml</b>: Extracts an element outer
     *       HTML (like "html", but includes the "current" tag).</li>
     *   <li><b>ownText</b>: Extracts the text owned by this element only;
     *       does not get the combined text of all children.</li>
     *   <li><b>data</b>: Extracts the combined data of a data-element (e.g.
     *       &lt;script&gt;).</li>
     *   <li><b>id</b>: Extracts the ID attribute of the element (if any).</li>
     *   <li><b>tagName</b>: Extract the name of the tag of the element.</li>
     *   <li><b>val</b>: Extracts the value of a form element
     *       (input, textarea, etc).</li>
     *   <li><b>className</b>: Extracts the literal value of the element's
     *       "class" attribute, which may include multiple class names,
     *       space separated.</li>
     *   <li><b>cssSelector</b>: Extracts a CSS selector that will uniquely
     *       select (identify) this element.</li>
     *   <li><b>attr(attributeKey)</b>: Extracts the value of the element
     *       attribute matching your replacement for "attributeKey"
     *       (e.g. "attr(title)" will extract the "title" attribute).</li>
     * </ul>
     * <p>
     * Typically, when specified as an attribute, implementors can use the
     * following:
     * </p>
     *
     * @param element the element to extract value on
     * @param extract the type of extraction to perform
     * @return the element value
     * @see Element
     */
    public static String getElementValue(Element element, String extract) {
        String ext = StringUtils.lowerCase(extract);
        if (StringUtils.isBlank(ext) || "text".equals(ext)) {
            return element.text();
        }
        if ("html".equals(ext)) {
            return element.html();
        }
        if ("outerhtml".equals(ext)) {
            return element.outerHtml();
        }
        if ("data".equals(ext)) {
            return element.data();
        }
        if ("id".equals(ext)) {
            return element.id();
        }
        if ("owntext".equals(ext)) {
            return element.ownText();
        }
        if ("tagname".equals(ext)) {
            return element.tagName();
        }
        if ("val".equals(ext)) {
            return element.val();
        }
        if ("classname".equals(ext)) {
            return element.className();
        }
        if ("cssselector".equals(ext)) {
            return element.cssSelector();
        }
        if (ext.startsWith("attr(")) {
            String attr = StringUtils.substringBetween(ext, "(", ")");
            if (StringUtils.isNotBlank(attr)) {
                return element.attr(attr);
            }
            LOG.warn("\"{}\" attribute is not valid.", ext);
            return null;
        }

        // if it reaches here... extract is not supported
        LOG.warn(
                "\"{}\" is not a supported extract type. "
                        + "\"text\" will be used.",
                extract);
        return element.text();
    }
}
