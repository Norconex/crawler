/* Copyright 2021-2026 Norconex Inc.
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

import static com.norconex.importer.util.DomUtil.toJSoupParser;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.norconex.importer.handler.CommonMatchers;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.handler.condition.ConfigurableCondition;
import com.norconex.importer.util.DomUtil;
import com.norconex.importer.util.chunk.ChunkedTextReader;
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
 * {@link com.norconex.importer.util.DomUtil#getElementValue(org.jsoup.nodes.Element,java.lang.String)}
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
 * @see <a href="https://crawler.norconex.com/docs/reference/importer/DomCondition">
 *      DomCondition configuration reference</a>
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class DomCondition
        implements ConfigurableCondition<DomConditionConfig> {

    private final DomConditionConfig configuration =
            new DomConditionConfig();

    @Override
    public boolean test(DocHandlerContext docCtx) throws IOException {

        // only proceed if we are dealing with a supported content type
        if (!configuration.getContentTypeMatcher().matches(
                docCtx.contentType().toString())) {
            return false;
        }

        var matches = new AtomicBoolean();
        ChunkedTextReader.builder()
                .charset(configuration.getSourceCharset())
                .fieldMatcher(configuration.getFieldMatcher())
                .maxChunkSize(-1) // disable chunking to not break the DOM.
                .build()
                .read(docCtx, chunk -> {
                    // only check if no successful match yet.
                    if (!matches.get() && testDocument(
                            Jsoup.parse(
                                    chunk.getText(),
                                    docCtx.reference(),
                                    toJSoupParser(
                                            configuration.getParser())))) {
                        matches.set(true);
                    }
                    return true;
                });
        return matches.get();
    }

    private boolean testDocument(Document doc) {
        var elms = doc.select(configuration.getSelector());
        // no elements matching
        if (elms.isEmpty()) {
            return false;
        }
        // one or more elements matching
        for (Element elm : elms) {
            var value =
                    DomUtil.getElementValue(elm, configuration.getExtract());
            if (configuration.getValueMatcher().getPattern() == null
                    || configuration.getValueMatcher().matches(value)) {
                return true;
            }
        }
        return false;
    }
}
