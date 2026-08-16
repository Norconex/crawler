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
package com.norconex.importer.handler.splitter.impl;

import java.nio.charset.Charset;

import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.handler.CommonMatchers;
import com.norconex.importer.handler.CommonRestrictions;
import com.norconex.importer.handler.splitter.BaseDocumentSplitterConfig;
import com.norconex.importer.util.DomUtil;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>Splits HTML, XHTML, or XML document on elements matching a given
 * selector.
 * </p>
 * <p>
 * This class constructs a DOM tree from the document content. That DOM tree
 * is loaded entirely into memory. Use this splitter with caution if you know
 * you'll need to parse huge files. It may be preferable to use a stream-based
 * approach if this is a concern (e.g., {@link XmlStreamSplitter}).
 * </p>
 * <p>
 * The <a href="http://jsoup.org/">jsoup</a> parser library is used to load a
 * document content into a DOM tree. Elements are referenced using a
 * <a href="http://jsoup.org/cookbook/extracting-data/selector-syntax">
 * CSS or JQuery-like syntax</a>.
 * </p>
 * <p>Should be used as a pre-parse handler.</p>
 *
 * <h2>Content-types</h2>
 * <p>
 * By default, this filter is restricted to (applies only to) documents matching
 * the restrictions returned by
 * {@link CommonRestrictions#domContentTypes(String)}.
 * You can specify your own content types if you know they represent a file
 * with HTML or XML-like markup tags.
 * </p>
 *
 * <p><b>Since 2.5.0</b>, when used as a pre-parse handler,
 * this class attempts to detect the content character
 * encoding unless the character encoding
 * was specified using {@link #setSourceCharset(String)}. Since document
 * parsing converts content to UTF-8, UTF-8 is always assumed when
 * used as a post-parse handler.
 * </p>
 *
 * <p><b>Since 2.8.0</b>, you can specify which parser to use when reading
 * documents. The default is "html" and will normalize the content
 * as HTML. This is generally a desired behavior, but this can sometimes
 * have your selector fail. If you encounter this
 * problem, try switching to "xml" parser, which does not attempt normalization
 * on the content. The drawback with "xml" is you may not get all HTML-specific
 * selector options to work.  If you know you are dealing with XML to begin
 * with, specifying "xml" should be a good option.
 * </p>
 *
 * @see XmlStreamSplitter
 * @see <a href="https://crawler.norconex.com/docs/reference/importer/DomSplitter">
 *      DomSplitter configuration reference</a>
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class DomSplitterConfig extends BaseDocumentSplitterConfig {

    /**
     * The CSS-like selector (see class documentation) identifying which
     * elements to split.
     * @param selector the selector
     * @return the selector
     */
    private String selector;

    /**
     * The presumed source character set.
     * @param sourceCharset character set of the source to be transformed
     * @return character set of the source to be transformed
     */
    private Charset sourceCharset = null;

    /**
     * The type of parser to use when creating the DOM-tree.
     * Default is <code>html</code>.
     * @param parser <code>html</code> or <code>xml</code>.
     * @return <code>html</code> or <code>xml</code>.
     */
    private String parser = DomUtil.PARSER_HTML;

    /**
     * Matcher of one or more fields to use as the source of content to split
     * into new documents, instead of the original document content.
     * @param fieldMatcher field matcher
     * @return field matcher
     */
    private final TextMatcher fieldMatcher = new TextMatcher();

    public DomSplitterConfig setFieldMatcher(TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }

    /**
     * The matcher of content types to apply splitting on. No attempt to
     * split documents of any other content types will be made. Default is
     * {@link CommonMatchers#DOM_CONTENT_TYPES}.
     * @param contentTypeMatcher content type matcher
     * @return content type matcher
     */
    private final TextMatcher contentTypeMatcher =
            CommonMatchers.domContentTypes();

    /**
     * The matcher of content types to apply splitting on. No attempt to
     * split documents of any other content types will be made. Default is
     * {@link CommonMatchers#DOM_CONTENT_TYPES}.
     * @param contentTypeMatcher content type matcher
     * @return this
     */
    public DomSplitterConfig setContentTypeMatcher(TextMatcher matcher) {
        contentTypeMatcher.copyFrom(matcher);
        return this;
    }
}
