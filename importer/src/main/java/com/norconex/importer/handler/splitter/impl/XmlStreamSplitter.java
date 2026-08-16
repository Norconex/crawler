/* Copyright 2020-2026 Norconex Inc.
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;

import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import com.norconex.commons.lang.io.CachedOutputStream;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.xml.XmlUtil;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.doc.DocMetaConstants;
import com.norconex.importer.handler.CommonRestrictions;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.handler.DocHandlerException;
import com.norconex.importer.handler.splitter.AbstractDocumentSplitter;
import com.norconex.importer.util.MatchUtil;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
@Data
public class XmlStreamSplitter
        extends AbstractDocumentSplitter<XmlStreamSplitterConfig> {

    private final XmlStreamSplitterConfig configuration =
            new XmlStreamSplitterConfig();

    @Override
    public void split(DocHandlerContext docCtx) throws IOException {

        if (!MatchUtil.matchesContentType(
                configuration.getContentTypeMatcher(), docCtx.contentType())) {
            return;
        }

        if (configuration.getFieldMatcher().isSet()) {
            // Fields
            for (Entry<String, List<String>> en : docCtx.metadata().matchKeys(
                    configuration.getFieldMatcher()).entrySet()) {
                for (String val : en.getValue()) {
                    doSplit(docCtx, new ByteArrayInputStream(val.getBytes()));
                }
            }
        } else {
            // Body
            doSplit(docCtx, docCtx.input().asInputStream());
        }
    }

    private void doSplit(DocHandlerContext docCtx, InputStream is)
            throws IOException {
        try (is) {
            var h = new XmlHandler(
                    docCtx, Arrays.asList(
                            StringUtils.split(
                                    configuration.getPath(), '/')),
                    docCtx.childDocs());
            XmlUtil.createSaxParserFactory().newSAXParser().parse(is, h);
        } catch (SAXException | IOException | ParserConfigurationException e) {
            throw new DocHandlerException(
                    "Could not split XML document: " + docCtx.reference(), e);
        }
    }

    static class XmlHandler extends DefaultHandler {

        private final List<String> splitPath;
        private final List<Doc> splitDocs;
        private final DocHandlerContext xmlDoc;
        private final List<String> currentPath = new ArrayList<>();
        private PrintWriter w;
        private CachedOutputStream out;

        public XmlHandler(
                DocHandlerContext xmlDoc,
                List<String> splitPath,
                List<Doc> splitDocs) {
            this.xmlDoc = xmlDoc;
            this.splitDocs = splitDocs;
            this.splitPath = splitPath;
        }

        @Override
        public void startElement(
                String uri, String localName, String qName,
                Attributes attributes) throws SAXException {

            currentPath.add(qName);

            if (currentPath.equals(splitPath)) {
                out = xmlDoc.streamFactory().newOuputStream();
                w = new PrintWriter(out);
            }

            if (w != null) {
                w.print('<');
                w.print(esc(qName));
                for (var i = 0; i < attributes.getLength(); i++) {
                    w.print(
                            ' ' + esc(attributes.getQName(i)) + "=\""
                                    + esc(attributes.getValue(i)) + "\"");
                }
                w.print('>');
            }
        }

        @Override
        public void characters(char[] ch, int start, int length)
                throws SAXException {
            if (w != null) {
                var ctnt = new String(ch, start, length);
                ctnt = ctnt.replaceFirst("^\\s+$", "");
                w.write(esc(ctnt));
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName)
                throws SAXException {
            try {
                if (w != null) {
                    w.print("</" + esc(qName) + ">");
                    if (currentPath.equals(splitPath)) {
                        w.flush();
                        var childMeta = new Properties();
                        childMeta.loadFromMap(xmlDoc.metadata());
                        var embedRef = Integer.toString(splitDocs.size());
                        @SuppressWarnings("resource")
                        var childDoc = new Doc(
                                xmlDoc.reference() + "!" + embedRef)
                                        .setInputStream(out.getInputStream())
                                        .setMetadata(childMeta);
                        w.close();
                        out = null;
                        w = null;
                        childDoc.addParentReference(xmlDoc.reference());
                        childMeta.set(
                                DocMetaConstants.EMBEDDED_REFERENCE, embedRef);
                        splitDocs.add(childDoc);
                    }
                }
            } catch (IOException e) {
                throw new SAXException(
                        "Cannot parse XML for document: "
                                + xmlDoc.reference(),
                        e);
            }

            if (!currentPath.isEmpty()) {
                currentPath.remove(currentPath.size() - 1);
            }
        }

        @Override
        public void warning(SAXParseException e) throws SAXException {
            LOG.warn("XML warning: {}.", e.getMessage(), e);
        }

        @Override
        public void error(SAXParseException e) throws SAXException {
            LOG.error("XML error: {}.", e.getMessage(), e);
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            LOG.error("XML fatal error: {}.", e.getMessage(), e);
        }

        private String esc(String txt) {
            return StringEscapeUtils.escapeXml11(txt);
        }
    }
}
