/* Copyright 2021-2024 Norconex Inc.
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

import java.util.Collections;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.commons.lang.text.TextMatcher;

/**
 * <p>
 * Commonly used {@link TextMatcher} instances.
 * Each matcher returned are fresh instances that can safely be modified.
 * </p>
 * <p>
 * As of <b>3.0.0</b>, content type constants are immutable {@link Set}
 * instances as opposed to arrays.
 * </p>
 */
public final class CommonMatchers {

    /**
     * <p>Base content types for XML: "application/xml" and "text/xml".</p>
     */
    public static final Set<String> XML_BASE_CONTENT_TYPES = Set.of(
            "application/xml",
            "text/xml");

    /**
     * <p>ATOM, RDF, RSS, and strict XML content types:</p>
     * <ul>
     *   <li>application/atom+xml</li>
     *   <li>application/rdf+xml</li>
     *   <li>application/rss+xml</li>
     *   <li>application/xml</li>
     *   <li>text/xml</li>
     * </ul>
     */
    public static final Set<String> XML_FEED_CONTENT_TYPES =
            Collections.unmodifiableSet(
                    CollectionUtil.unionSet(
                            XML_BASE_CONTENT_TYPES,
                            "application/atom+xml",
                            "application/rdf+xml",
                            "application/rss+xml"));

    /**
     * <p>HTML/XHTML content types:</p>
     * <ul>
     *   <li>application/vnd.wap.xhtml+xml</li>
     *   <li>application/xhtml+xml</li>
     *   <li>text/html</li>
     * </ul>
     */
    public static final Set<String> HTML_CONTENT_TYPES = Set.of(
            "application/vnd.wap.xhtml+xml",
            "application/xhtml+xml",
            "text/html");

    /**
     * <p>HTML, XHTML, and XML-based content types:</p>
     * <ul>
     *   <li>application/atom+xml</li>
     *   <li>application/mathml+xml</li>
     *   <li>application/rss+xml</li>
     *   <li>application/xhtml+xml</li>
     *   <li>application/xml</li>
     *   <li>application/xslt+xml</li>
     *   <li>image/svg+xml</li>
     *   <li>text/xml</li>
     * </ul>
     */
    public static final Set<String> XML_CONTENT_TYPES =
            Collections.unmodifiableSet(
                    CollectionUtil.unionSet(
                            XML_FEED_CONTENT_TYPES,
                            "application/xhtml+xml",
                            "application/xslt+xml",
                            "application/mathml+xml",
                            "image/svg+xml"));

    /**
     * <p>Content types representing a document object model:</p>
     * <ul>
     *   <li>application/atom+xml</li>
     *   <li>application/mathml+xml</li>
     *   <li>application/rss+xml</li>
     *   <li>application/vnd.wap.xhtml+xml</li>
     *   <li>application/x-asp</li>
     *   <li>application/xhtml+xml</li>
     *   <li>application/xml</li>
     *   <li>application/xslt+xml</li>
     *   <li>image/svg+xml</li>
     *   <li>text/html</li>
     *   <li>text/xml</li>
     * </ul>
     */
    public static final Set<String> DOM_CONTENT_TYPES =
            Collections.unmodifiableSet(
                    CollectionUtil.unionSet(
                            XML_CONTENT_TYPES,
                            HTML_CONTENT_TYPES,
                            "application/mathml+xml",
                            "application/x-asp"));

    /**
     * <p>Content types for natively supported Java ImageIO images.</p>
     * <ul>
     *   <li>image/bmp</li>
     *   <li>image/gif</li>
     *   <li>image/jpeg</li>
     *   <li>image/png</li>
     *   <li>image/vnd.wap.wbmp</li>
     *   <li>image/x-windows-bmp</li>
     * </ul>
     */
    public static final Set<String> IMAGE_IO_CONTENT_TYPES = Set.of(
            "image/bmp",
            "image/gif",
            "image/jpeg",
            "image/png",
            "image/vnd.wap.wbmp",
            "image/x-windows-bmp");

    private CommonMatchers() {
    }

    /**
     * <p>
     * Matcher for common content-types defining a DOM document. That is,
     * documents that are HTML, XHTML, or XML-based.
     * The value has to be any of these to trigger a match (case-insensitive):
     * </p>
     * {@link com.norconex.importer.handler.CommonMatchers#DOM_CONTENT_TYPES}
     * @return text matcher
     */
    public static TextMatcher domContentTypes() {
        return csv(DOM_CONTENT_TYPES);
    }

    /**
     * <p>
     * Matcher for common content-types defining an XML feed (RSS, Atom).
     * Because XML feeds are not always declaring their content type properly,
     * this matcher will also match base XML content types.
     * The value has to be any of these to trigger a match (case-insensitive):
     * </p>
     * {@link com.norconex.importer.handler.CommonMatchers#XML_FEED_CONTENT_TYPES}
     * @return text matcher
     */
    public static TextMatcher xmlFeedContentTypes() {
        return csv(XML_FEED_CONTENT_TYPES);
    }

    /**
     * <p>
     * Matcher for common content-types defining an HTML or XHTML document.
     * The value has to be any of these to trigger a match (case-insensitive):
     * </p>
     * {@link com.norconex.importer.handler.CommonMatchers#HTML_CONTENT_TYPES}
     * @return text matcher
     */
    public static TextMatcher htmlContentTypes() {
        return csv(HTML_CONTENT_TYPES);
    }

    /**
     * <p>
     * Common content-types defining an XML document.
     * The value has to be any of these to trigger a match (case-insensitive):
     * </p>
     * {@link com.norconex.importer.handler.CommonMatchers#XML_CONTENT_TYPES}
     * @return text matcher
     */
    public static TextMatcher xmlContentTypes() {
        return csv(XML_CONTENT_TYPES);
    }

    /**
     * <p>
     * Content types of standard image format supported by all Java ImageIO
     * implementations: JPEG, PNG, GIF, BMP, WBMP.
     * The value has to be any of these to trigger a match (case-insensitive):
     * </p>
     * {@link com.norconex.importer.handler.CommonMatchers#IMAGE_IO_CONTENT_TYPES}
     * @return text matcher
     */
    public static TextMatcher imageIOStandardContentTypes() {
        return csv(IMAGE_IO_CONTENT_TYPES);
    }

    /**
     * <p>
     * Matches all content types.
     * </p>
     * @return text matcher
     */
    public static TextMatcher all() {
        return TextMatcher.regex(".*");
    }

    private static TextMatcher csv(Set<String> values) {
        return TextMatcher
                .csv(StringUtils.join(values, ','))
                .ignoreCase();
    }
}
