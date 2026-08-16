/* Copyright 2015-2024 Norconex Inc.
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
package com.norconex.importer.handler;

import java.util.Collection;

import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.map.PropertyMatcher;
import com.norconex.commons.lang.map.PropertyMatchers;
import com.norconex.commons.lang.text.TextMatcher;

/**
 * Commonly encountered restrictions that can be applied to {@link Properties}
 * instances. Each method return a newly created
 * list that can safely be modified without impacting subsequent calls.
 */
public final class CommonRestrictions {

    private CommonRestrictions() {
    }

    /**
     * <p>
     * Common content-types defining an XML feed (RSS, Atom).
     * The field has to contain one of these for the restriction to apply:
     * </p>
     * {@link com.norconex.importer.handler.CommonMatchers#XML_FEED_CONTENT_TYPES}
     * @param field name of Properties field
     * @return list of restrictions
     */
    public static PropertyMatchers xmlFeedContentTypes(String field) {
        return basicMatcherIgnoreCase(
                field, CommonMatchers.XML_FEED_CONTENT_TYPES);
    }

    /**
     * <p>
     * Common content-types defining a DOM document. That is, documents that
     * are HTML, XHTML, or XML-based.
     * The field has to contain one of these for the restriction to apply:
     * </p>
     * {@link com.norconex.importer.handler.CommonMatchers#DOM_CONTENT_TYPES}
     * @param field name of Properties field
     * @return list of restrictions
     */
    public static PropertyMatchers domContentTypes(String field) {
        return basicMatcherIgnoreCase(field, CommonMatchers.DOM_CONTENT_TYPES);
    }

    /**
     * <p>
     * Default content-types defining an HTML or XHTML document.
     * The field has to contain one of these for the restriction to apply:
     * </p>
     * {@link com.norconex.importer.handler.CommonMatchers#HTML_CONTENT_TYPES}
     * @param field name of Properties field
     * @return list of restrictions
     */
    public static PropertyMatchers htmlContentTypes(String field) {
        return basicMatcherIgnoreCase(field, CommonMatchers.HTML_CONTENT_TYPES);
    }

    /**
     * <p>
     * Common content-types defining an XML document.
     * The field has to contain one of these for the restriction to apply:
     * </p>
     * {@link com.norconex.importer.handler.CommonMatchers#XML_CONTENT_TYPES}
     * @param field name of Properties field
     * @return list of restrictions
     */
    public static PropertyMatchers xmlContentTypes(String field) {
        return basicMatcherIgnoreCase(field, CommonMatchers.XML_CONTENT_TYPES);
    }

    /**
     * <p>
     * Content types of standard image format supported by all Java ImageIO
     * implementations: JPEG, PNG, GIF, BMP, WBMP.
     * The field has to contain one of these for the restriction to apply:
     * </p>
     * {@link com.norconex.importer.handler.CommonMatchers#IMAGE_IO_CONTENT_TYPES}
     * @param field name of Properties field
     * @return list of restrictions
         */
    public static PropertyMatchers imageIOStandardContentTypes(String field) {
        return basicMatcherIgnoreCase(
                field, CommonMatchers.IMAGE_IO_CONTENT_TYPES);
    }

    private static PropertyMatchers basicMatcherIgnoreCase(
            String key, Collection<String> contentTypes) {
        var matchers = new PropertyMatchers();
        for (String contentType : contentTypes) {
            matchers.add(
                    new PropertyMatcher(
                            TextMatcher.basic(key).setIgnoreCase(true),
                            TextMatcher.basic(contentType)
                                    .setIgnoreCase(true)));
        }
        return matchers;
    }
}
