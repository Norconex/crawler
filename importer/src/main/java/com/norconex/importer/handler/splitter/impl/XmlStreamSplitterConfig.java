/* Copyright 2020-2024 Norconex Inc.
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

import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.handler.CommonMatchers;
import com.norconex.importer.handler.CommonRestrictions;
import com.norconex.importer.handler.splitter.BaseDocumentSplitterConfig;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>Splits XML document on a specific element.
 * </p>
 * <p>
 * This class is suited for large XML documents. It will read the XML as a
 * stream and split as it is read, preserving memory during parsing.
 * For this reason, element matching is not as flexible as DOM-based XML
 * parsers, such as {@link DomSplitter}, but is more efficient on large
 * documents.
 * </p>
 *
 * <h2>Element matching</h2>
 * <p>
 * To identify the element to split on, you give the full path to it from
 * the document root, where each element is separated by a forward slash.
 * Let's take this XML as an example:
 * </p>
 * <pre>
 * &lt;animals&gt;
 *   &lt;species name="mouse"&gt;
 *     &lt;animal&gt;
 *       &lt;name&gt;Itchy&lt;/name&gt;
 *       &lt;race&gt;cartoon&lt;/race&gt;
 *     &lt;/animal&gt;
 *   &lt;/species&gt;
 *   &lt;species name="cat"&gt;
 *     &lt;animal&gt;
 *       &lt;name&gt;Scratchy&lt;/name&gt;
 *       &lt;race&gt;cartoon&lt;/race&gt;
 *     &lt;/animal&gt;
 *   &lt;/species&gt;
 * &lt;/animals&gt;
 * </pre>
 * <p>
 * To split on <code>&lt;animal&gt;</code>, you would use this path:
 * </p>
 * <pre>
 * /animals/species/animal
 * </pre>
 *
 * <p>Should be used as a pre-parse handler.</p>
 *
 * <h2>Content-types</h2>
 * <p>
 * By default, this filter is restricted to (applies only to) documents matching
 * the restrictions returned by
 * {@link CommonRestrictions#xmlContentTypes(String)}.
 * You can specify your own restrictions to further narrow, or loosen what
 * documents this splitter applies to.
 * </p>
 *
 * @see DomSplitter
 * @see <a href="https://crawler.norconex.com/docs/reference/importer/XmlStreamSplitter">
 *      XmlStreamSplitter configuration reference</a>
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class XmlStreamSplitterConfig extends BaseDocumentSplitterConfig {

    private String path;

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
     * Matcher of one or more fields to use as the source of content to split
     * into new documents, instead of the original document content.
     * @param fieldMatcher field matcher
     * @return field matcher
     */
    private final TextMatcher fieldMatcher = new TextMatcher();

    /**
     * The matcher of content types to apply splitting on. No attempt to
     * split documents of any other content types will be made. Default is
     * {@link CommonMatchers#DOM_CONTENT_TYPES}.
     * @param contentTypeMatcher content type matcher
     * @return this
     */
    public XmlStreamSplitterConfig setContentTypeMatcher(
            TextMatcher matcher) {
        contentTypeMatcher.copyFrom(matcher);
        return this;
    }

    public XmlStreamSplitterConfig setFieldMatcher(TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }
}
