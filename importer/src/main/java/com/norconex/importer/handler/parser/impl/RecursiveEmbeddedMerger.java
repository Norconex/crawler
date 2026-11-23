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

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.LinkedList;
import java.util.Optional;

import org.apache.tika.io.TikaInputStream;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.ParserDecorator;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import com.norconex.commons.lang.file.ContentType;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.doc.ContentTypeDetector;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.handler.parser.impl.nativemeta.NativeMetadata;

import lombok.extern.slf4j.Slf4j;

@Slf4j
class RecursiveEmbeddedMerger extends ParserDecorator {
    private static final long serialVersionUID = 1L;

    private final transient DocHandlerContext docCtx;
    private final transient Writer writer;
    private final EmbeddedConfig embeddedConfig;
    private boolean isMasterDoc = true;
    private final LinkedList<ContentType> typesHierarchy = new LinkedList<>();

    public RecursiveEmbeddedMerger(
            Parser parser,
            Writer writer,
            DocHandlerContext docCtx,
            EmbeddedConfig embeddedConfig) {
        super(parser);
        this.writer = writer;
        this.docCtx = docCtx;
        this.embeddedConfig = embeddedConfig;
    }

    @Override
    public void parse(
            TikaInputStream stream,
            ContentHandler handler,
            Metadata tikaMeta,
            ParseContext context)
            throws IOException, SAXException, TikaException {

        var resName = tikaMeta.get(TikaCoreProperties.RESOURCE_NAME_KEY);

        // Extract only up to specified max depth
        var embedDepth = typesHierarchy.size();
        var maxDepth = embeddedConfig.getMaxEmbeddedDepth();
        if (maxDepth >= 0 && embedDepth > maxDepth) {
            LOG.debug(
                    "Skipping embedded document {} which is over max "
                            + "depth: {}",
                    maxDepth, resName);
            return;
        }

        // Don't parse if unwanted embedded of parent content type
        var parentType = Optional.ofNullable(typesHierarchy.peekLast())
                .map(ContentType::toBaseTypeString)
                .orElse(null);
        if (!isMasterDoc && TextMatcher.anyMatches(
                embeddedConfig.getSkipEmbeddedOfContentTypes(),
                parentType)) {
            LOG.debug(
                    "Skipping embedded document {} "
                            + "of parent content type: {}.",
                    resName, parentType);
            return;
        }

        // Content type from container or detected
        ContentType currentType;
        if (isMasterDoc) {
            isMasterDoc = false;
            currentType = docCtx.contentType();
        } else {
            currentType = ContentTypeDetector.detect(stream, resName);
        }

        // Don't parse if unwanted embedded content type
        if (TextMatcher.anyMatches(
                embeddedConfig.getSkipEmbeddedContentTypes(),
                currentType.toBaseTypeString())) {
            LOG.debug(
                    "Skipping embedded document {} "
                            + "with content type: {}.",
                    resName, currentType);
            return;
        }

        // All good, parse.
        typesHierarchy.add(currentType);
        var nativeFields = NativeMetadata.read(
                stream, currentType,
                tikaMeta.get(HttpHeaders.CONTENT_ENCODING));
        super.parse(
                stream,
                new BodyContentHandler(writer), tikaMeta, context);
        nativeFields.supersede(tikaMeta);
        TikaUtil.metadataToProperties(tikaMeta, docCtx.metadata());
        nativeFields.addTo(docCtx.metadata());
        typesHierarchy.pollLast();
    }
}
