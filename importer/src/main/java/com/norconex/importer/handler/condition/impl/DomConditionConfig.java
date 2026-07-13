/* Copyright 2021-2025 Norconex Inc.
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
package com.norconex.importer.handler.condition.impl;

import java.nio.charset.Charset;

import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.handler.CommonMatchers;
import com.norconex.importer.handler.condition.BaseConditionConfig;
import com.norconex.importer.util.DomUtil;
import com.optimaize.langdetect.text.TextFilter;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * A condition using a Document Object Model (DOM) representation of an HTML,
 * XHTML, or XML document content to match an element, attribute or value.
 * </p>
 * <p>
 * In order to construct a DOM tree, text is loaded entirely
 * into memory. It uses the document content to create the DOM by default,
 * but it can also use metadata fields. If more than one metadata field
 * values are identified as the source of DOM content, only one needs to
 * match for this condition to be <code>true</code>.
 * Use this condition with caution if you know you'll need to parse
 * huge files. You can use {@link TextFilter} instead if this is a
 * concern.
 * </p>
 * <p>
 * The <a href="http://jsoup.org/">jsoup</a> parser library is used to load the
 * content into a DOM tree. Elements are referenced using a
 * <a href="http://jsoup.org/cookbook/extracting-data/selector-syntax">
 * CSS or JQuery-like syntax</a>.
 * </p>
 * <p>
 * The use of a value matcher is optional. Without one, any element found
 * by the provided DOM selector will constitute a match.
 * If both a DOM selector and a value matcher are provided,
 * the matching selector element value(s) will be retrieved and the
 * value matcher will be applied against it (or them) for a match.
 * </p>
 * <p>It is possible to control what gets extracted
 * exactly for matching purposes thanks to the "extract" argument of the
 * new method {@link #setExtract(String)}. Possible values are:
 * </p>
 * {@nx.include com.norconex.importer.util.DomUtil#extract}
 * <p>
 * Should be used as a pre-parse handler.
 * </p>
 *
 * <h2>Content-types</h2>
 * <p>
 * If you are dealing with multiple document types and you are using this
 * condition on the document content, it is important
 * to restrict this condition to text-based XML-like content only to
 * prevent DOM-parsing errors.
 * </p>
 * <p>
 * By default this condition only applies to documents matching
 * the content types listed in {@link CommonMatchers#DOM_CONTENT_TYPES}.
 * Other content types always make this condition <code>false</code>.
 * </p>
 * <p>
 * You can overwrite these default content types by providing your own
 * content type matcher. Make sure the content types you use represent a file
 * with HTML or XML-like markup tags.
 * </p>
 *
 * {@nx.include com.norconex.importer.handler.condition.AbstractCharStreamCondition#charEncoding}
 *
 * <h2>Character encoding</h2>
 * <p>When used as a pre-parse handler, this condition uses the detected
 * character encoding unless the character encoding
 * was specified using {@link #setSourceCharset(String)}. Since document
 * parsing should always converts content to UTF-8, UTF-8 is always
 * assumed when used as a post-parse handler.
 * </p>
 *
 * <h2>XML vs HTML</h2>
 * <p>You can specify which DOM parser to use when reading
 * documents. The default is "html" and will try to normalize/fix the content
 * as HTML. This is generally a desired behavior, but this can sometimes
 * have your selector fail. If you encounter this
 * problem, try switching to "xml" parser, which does not attempt normalization
 * on the content. The drawback with "xml" is you may not get all HTML-specific
 * selector options to work.  If you know you are dealing with XML to begin
 * with, specifying "xml" is a good option.
 * </p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.condition.impl.DomCondition"
 *     {@nx.include com.norconex.importer.handler.condition.AbstractCharStreamCondition#attributes}
 *     selector="(selector syntax)"
 *     parser="[html|xml]"
 *     extract="[text|html|outerHtml|ownText|data|tagName|val|className|cssSelector|attr(attributeKey)]">
 *
 *   <fieldMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *     (Optional expression matching one or more fields where the DOM text is located.)
 *   </fieldMatcher>
 *   <valueMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *     (Optional expression matching selector extracted value.)
 *   </valueMatcher>
 *   <contentTypeMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *     (Optional expression overwriting the content types this condition applies to.)
 *   </contentTypeMatcher>
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <!-- Matches an HTML page that has one or more GIF images in it: -->
 * <condition class="DomCondition" selector="img[src$=.gif]" onMatch="exclude"/>
 *
 * <!-- Matches an HTML page that has a paragraph tag with a class called
 *      "disclaimer" and a value containing "skip me": -->
 * <condition class="DomCondition" selector="p.disclaimer" onMatch="exclude">
 *   <valueMatcher method="regex">\bskip me\b</valueMatcher>
 * </condition>
 * }
 *
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class DomConditionConfig extends BaseConditionConfig {

    private final TextMatcher fieldMatcher = new TextMatcher();
    private final TextMatcher valueMatcher = new TextMatcher();
    private final TextMatcher contentTypeMatcher =
            CommonMatchers.domContentTypes();
    private String selector;
    private String extract;
    private String parser = DomUtil.PARSER_HTML;
    /**
     * The presumed source character encoding. Usually ignored and presumed
     * to be UTF-8 if the document has been parsed already.
     * @param sourceCharset character encoding of the source to be transformed
     * @return character encoding of the source to be transformed
     */
    private Charset sourceCharset;

    /**
     * Gets this filter field matcher.
     * @return field matcher
     */
    public TextMatcher getFieldMatcher() {
        return fieldMatcher;
    }

    /**
     * Sets this condition field matcher.
     * @param fieldMatcher field matcher
     */
    public DomConditionConfig setFieldMatcher(TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }

    /**
     * Gets this condition value matcher.
     * @return value matcher
     */
    public TextMatcher getValueMatcher() {
        return valueMatcher;
    }

    /**
     * Sets this condition value matcher.
     * @param valueMatcher value matcher
     */
    public DomConditionConfig setValueMatcher(TextMatcher valueMatcher) {
        this.valueMatcher.copyFrom(valueMatcher);
        return this;
    }

    /**
     * Gets this condition content-type matcher.
     * @return content-type matcher
     */
    public TextMatcher getContentTypeMatcher() {
        return contentTypeMatcher;
    }

    /**
     * Sets this condition content-type matcher.
     * @param contentTypeMatcher content-type matcher
     */
    public DomConditionConfig setContentTypeMatcher(
            TextMatcher contentTypeMatcher) {
        this.contentTypeMatcher.copyFrom(contentTypeMatcher);
        return this;
    }

    /**
     * Gets what should be extracted for the value. One of
     * "text" (default), "html", or "outerHtml". {@code null} means
     * this class will use the default ("text").
     * @return what should be extracted for the value
     */
    public String getExtract() {
        return extract;
    }

    /**
     * Sets what should be extracted for the value. One of
     * "text" (default), "html", or "outerHtml". {@code null} means
     * this class will use the default ("text").
     * @param extract what should be extracted for the value
     */
    public DomConditionConfig setExtract(String extract) {
        this.extract = extract;
        return this;
    }

    /**
     * Gets the parser to use when creating the DOM-tree.
     * @return <code>html</code> (default) or <code>xml</code>.
     */
    public String getParser() {
        return parser;
    }

    /**
     * Sets the parser to use when creating the DOM-tree.
     * @param parser <code>html</code> or <code>xml</code>.
     */
    public DomConditionConfig setParser(String parser) {
        this.parser = parser;
        return this;
    }

    public String getSelector() {
        return selector;
    }

    public DomConditionConfig setSelector(String selector) {
        this.selector = selector;
        return this;
    }
}
