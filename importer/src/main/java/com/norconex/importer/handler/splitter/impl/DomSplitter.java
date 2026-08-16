/* Copyright 2015-2026 Norconex Inc.
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

import java.io.IOException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.norconex.commons.lang.io.CachedInputStream;
import com.norconex.commons.lang.map.Properties;
import com.norconex.importer.charset.CharsetUtil;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.doc.DocMetaConstants;
import com.norconex.importer.handler.CommonRestrictions;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.handler.DocHandlerException;
import com.norconex.importer.handler.splitter.AbstractDocumentSplitter;
import com.norconex.importer.util.DomUtil;
import com.norconex.importer.util.MatchUtil;

import lombok.Data;

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
public class DomSplitter extends AbstractDocumentSplitter<DomSplitterConfig> {

    private final DomSplitterConfig configuration = new DomSplitterConfig();

    @Override
    public void split(DocHandlerContext docCtx) throws DocHandlerException {

        if (!MatchUtil.matchesContentType(
                configuration.getContentTypeMatcher(), docCtx.contentType())) {
            return;
        }

        // Fields
        if (configuration.getFieldMatcher().isSet()) {
            docCtx.childDocs();
            docCtx.metadata().matchKeys(
                    configuration.getFieldMatcher())
                    .forEach((k, vals) -> vals.forEach(
                            v -> parse(docCtx, Jsoup.parse(
                                    v, docCtx.reference(),
                                    DomUtil.toJSoupParser(
                                            configuration.getParser())))));
        } else {
            // Body
            try {
                var inputCharset = CharsetUtil.firstNonNullOrUTF8(
                        docCtx.parseState(),
                        configuration.getSourceCharset(),
                        docCtx.charset());
                var soupDoc = Jsoup.parse(
                        docCtx.input().asInputStream(),
                        inputCharset.toString(),
                        docCtx.reference(),
                        DomUtil.toJSoupParser(configuration.getParser()));
                parse(docCtx, soupDoc);
            } catch (IOException e) {
                throw new DocHandlerException(
                        "Cannot parse document into a DOM-tree.", e);
            }
        }
    }

    private void parse(DocHandlerContext docCtx, Document soupDoc) {
        var elms = soupDoc.select(configuration.getSelector());

        // if there only 1 element matched, make sure it is not the same as
        // the parent document to avoid infinite loops (the parent
        // matching itself recursively).
        if (elms.size() == 1) {
            var matchedElement = elms.get(0);
            var parentElement = getBodyElement(soupDoc);
            if (matchedElement.equals(parentElement)) {
                return;
            }
        }

        // process "legit" child elements
        var docs = docCtx.childDocs();
        for (Element elm : elms) {
            var childMeta = new Properties();
            childMeta.loadFromMap(docCtx.metadata());
            var childContent = elm.outerHtml();
            var childEmbedRef = elm.cssSelector();
            var childRef = docCtx.reference() + "!" + childEmbedRef;
            CachedInputStream content = null;
            if (childContent.length() > 0) {
                content = docCtx.streamFactory().newInputStream(
                        childContent);
            } else {
                content = docCtx.streamFactory().newInputStream();
            }
            @SuppressWarnings("resource")
            var childDoc = new Doc(childRef)
                    .setInputStream(content)
                    .setMetadata(childMeta)
                    .addParentReference(docCtx.reference());
            childMeta.set(DocMetaConstants.EMBEDDED_REFERENCE, childEmbedRef);
            docs.add(childDoc);
        }
    }

    private Element getBodyElement(Document soupDoc) {
        var body = soupDoc.body();
        if (body.childNodeSize() == 1) {
            return body.child(0);
        }
        return null;
    }
}
