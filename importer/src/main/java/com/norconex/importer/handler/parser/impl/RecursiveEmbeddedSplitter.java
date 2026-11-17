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
package com.norconex.importer.handler.parser.impl;

import static com.norconex.importer.doc.DocMetaConstants.EMBEDDED_REFERENCE;
import static com.norconex.importer.doc.DocMetaConstants.EMBEDDED_TYPE;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.ParserDecorator;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import com.norconex.commons.lang.file.ContentType;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.doc.ContentTypeDetector;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.handler.DocHandlerContext;

import lombok.extern.slf4j.Slf4j;

@Slf4j
class RecursiveEmbeddedSplitter extends ParserDecorator {
    private static final long serialVersionUID = 1L;

    private final transient DocHandlerContext docCtx;
    private boolean isMasterDoc = true;
    private int embedCount;
    private final transient List<Doc> embeddedDocs;
    private final EmbeddedConfig embeddedConfig;

    public RecursiveEmbeddedSplitter(
            Parser parser,
            DocHandlerContext docCtx,
            List<Doc> embeddedDocs,
            EmbeddedConfig embeddedConfig) {
        super(parser);
        this.docCtx = docCtx;
        this.embeddedDocs = embeddedDocs;
        this.embeddedConfig = embeddedConfig;
    }

    @Override
    public void parse(
            TikaInputStream input,
            ContentHandler handler,
            Metadata tikaMeta,
            ParseContext context)
            throws IOException, SAXException, TikaException {

        var resName = tikaMeta.get(TikaCoreProperties.RESOURCE_NAME_KEY);

        // Container doc:
        if (isMasterDoc) {
            isMasterDoc = false;
            super.parse(input, handler, tikaMeta, context);
            TikaUtil.metadataToProperties(tikaMeta, docCtx.metadata());
            return;
        }

        var embedDepth = docCtx.parentReferences().size() + 1;
        var maxDepth = embeddedConfig.getMaxEmbeddedDepth();
        // Extract only up to specified max depth
        if (maxDepth >= 0 && embedDepth > maxDepth) {
            LOG.warn(
                    "Skipping embedded document {} which is over max "
                            + "depth: {}",
                    maxDepth, resName);
            return;
        }

        // Don't parse if unwanted embedded of parent content type
        var parentType = docCtx.contentType().toBaseTypeString();
        if (TextMatcher.anyMatches(
                embeddedConfig.getSkipEmbeddedOfContentTypes(),
                parentType)) {
            LOG.debug(
                    "Skipping embedded document {} "
                            + "of parent content type: {}.",
                    tikaMeta.get(TikaCoreProperties.RESOURCE_NAME_KEY),
                    parentType);
            return;
        }

        // Embedded doc content type:
        var embedContentType = ContentTypeDetector.detect(input);

        // Don't parse if unwanted embedded content type
        if (TextMatcher.anyMatches(
                embeddedConfig.getSkipEmbeddedContentTypes(),
                embedContentType.toBaseTypeString())) {
            LOG.debug(
                    "Skipping embedded document {} "
                            + "with content type: {}.",
                    tikaMeta.get(TikaCoreProperties.RESOURCE_NAME_KEY),
                    embedContentType);
            return;
        }

        embedCount++;
        var embedMeta = new Properties();
        TikaUtil.metadataToProperties(tikaMeta, embedMeta);
        var embedDoc = createEmbeddedDoc(tikaMeta, embedMeta, embedCount);
        embedDoc.setParentReferences(docCtx.parentReferences());

        // Read the steam into cache for reuse since Tika will
        // close the original stream on us causing exceptions later.
        try (var embedOutput = docCtx.streamFactory().newOuputStream()) {
            IOUtils.copy(input, embedOutput);
            //TODO have metadata + prent added in createEmbeddedDoc?
            embedDoc.setInputStream(embedOutput.getInputStream())
                    .addParentReference(docCtx.reference())
                    .setMetadata(embedMeta);
            embeddedDocs.add(embedDoc);
        }
    }

    private Doc createEmbeddedDoc(
            Metadata tikaMeta, Properties embedMeta, int embedCount) {

        String name;

        // Package item file name (e.g. a file in a zip)
        name = tikaMeta.get(TikaCoreProperties.EMBEDDED_RELATIONSHIP_ID);
        if (StringUtils.isNotBlank(name)) {
            return doCreateEmbeddedDoc(name, embedMeta, "package-file");
        }

        // Name of Embedded file in regular document
        // (e.g. excel file in a word doc)
        name = tikaMeta.get(TikaCoreProperties.RESOURCE_NAME_KEY);
        if (StringUtils.isNotBlank(name)) {
            return doCreateEmbeddedDoc(name, embedMeta, "file-file");
        }

        // Name of embedded content in regular document
        // (e.g. image with no name in a word doc)
        // Make one up with content type
        // (which should be OK most of the time).
        name = tikaMeta.get(HttpHeaders.CONTENT_TYPE);
        if (StringUtils.isNotBlank(name)) {
            var ct = ContentType.valueOf(name);
            if (ct != null) {
                return doCreateEmbeddedDoc(
                        "embedded-" + embedCount + "." + ct.getExtension(),
                        embedMeta,
                        "file-file");
            }
        }

        // Default... we could not find any name so make a unique one.
        return doCreateEmbeddedDoc(
                "embedded-" + embedCount + ".unknown",
                embedMeta,
                "unknown");
    }

    private Doc doCreateEmbeddedDoc(
            String embedRef, Properties embedMeta, String embedType) {
        var docRecord = new Doc(docCtx.reference() + "!" + embedRef);
        embedMeta.set(EMBEDDED_REFERENCE, embedRef);
        embedMeta.set(EMBEDDED_TYPE, embedType);
        return docRecord;
    }
}
