/* Copyright 2010-2026 Norconex Inc.
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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Optional.ofNullable;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.exception.ZeroByteFileException;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;

import com.norconex.commons.lang.io.CachedInputStream;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.doc.ContentTypeDetector;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.handler.parser.ParseState;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * Parser class when no other handlers are specified.
 * The importer uses Apache Tika parser in its own way with default
 * settings common for most senarios.
 * If you want to use and configure Tika yourself, use
 * {@link TikaParser}.
 */
@Slf4j
@EqualsAndHashCode
@ToString
public class DefaultParser
        implements ConfigurableDocHandler<DefaultParserConfig> {

    @Getter
    private final DefaultParserConfig configuration = new DefaultParserConfig();
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private Parser tikaParser;
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private AtomicBoolean initialized = new AtomicBoolean();

    private static final String TIKA_TESSERACT_OCR_PARSER =
            "org.apache.tika.parser.ocr.TesseractOCRParser";

    @Override
    public void init() throws IOException {

        TikaXmlParserPool.configure(configuration.getXmlParserPoolSize());
        fixTikaInitWarning();
        tikaParser = TikaParserPool.create(
                configuration.getPoolSize(), this::createTikaParser);
        applyGrobidConfig();
        initialized.set(true);
    }

    private Parser createTikaParser() throws IOException {
        var parser = DefTikaConfigurer.configure(configuration);
        // Use the same custom MIME-type detector as ContentTypeDetector so
        // that types like application/vnd.xfdl are correctly routed to their
        // registered parsers (e.g. XfdlTikaParser).
        parser.setDetector(ContentTypeDetector.getDetector());
        return parser;
    }

    @Override
    public void close() {
        if (tikaParser instanceof TikaParserPool pool) {
            pool.close();
        }
    }

    @Override
    public boolean handle(DocHandlerContext ctx) throws IOException {
        var tikaMetadata = new Metadata();
        var contentType = ctx.contentType();

        if (contentType == null) {
            throw new IOException("Doc must have a content-type.");
        }

        List<Doc> embeddedDocs = new ArrayList<>();
        tikaMetadata.set(HttpHeaders.CONTENT_TYPE, contentType.toString());
        tikaMetadata.set(
                TikaCoreProperties.RESOURCE_NAME_KEY,
                ctx.reference());
        tikaMetadata.set(
                HttpHeaders.CONTENT_ENCODING,
                ofNullable(ctx.charset())
                        .map(Charset::toString)
                        .orElse(null));

        Parser recursiveParser = null;
        try (var input = CachedInputStream.cache(ctx.input().asInputStream());
                var output = ctx.output().asWriter(UTF_8)) {

            tikaMetadata.set(
                    HttpHeaders.CONTENT_LENGTH,
                    Long.toString(input.length()));
            recursiveParser =
                    createRecursiveParser(ctx, output, embeddedDocs);
            var context = new ParseContext();
            context.set(Parser.class, recursiveParser);

            var pdfConfig = new PDFParserConfig();
            if (!configuration.getOcrConfig().isDisabled()) {
                pdfConfig.setExtractInlineImages(true);
            } else {
                var pdfOcr = new org.apache.tika.parser.pdf.OcrConfig();
                pdfOcr.setStrategy(
                        org.apache.tika.parser.pdf.OcrConfig.Strategy.NO_OCR);
                pdfConfig.setOcr(pdfOcr);
            }
            pdfConfig.setSuppressDuplicateOverlappingText(true);
            context.set(PDFParserConfig.class, pdfConfig);

            recursiveParser.parse(
                    TikaInputStream.get(input),
                    new BodyContentHandler(output), tikaMetadata, context);
        } catch (ZeroByteFileException e) {
            LOG.info("Document has no content: {}", ctx.reference());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
        ctx.parseState(ParseState.POST);
        ctx.childDocs().addAll(embeddedDocs);
        // A container that was split into documents of their own can be
        // discarded, leaving those to carry on. Never one that is merged,
        // nor one that held nothing to split off.
        if (!embeddedDocs.isEmpty() && isSplit(ctx) && TextMatcher.anyMatches(
                configuration.getEmbeddedConfig()
                        .getDiscardContainerContentTypes(),
                ctx.contentType().toBaseTypeString())) {
            ctx.rejectedBy(this);
            return false;
        }
        return true;
    }

    /** Whether the document's embedded documents are split, not merged. */
    private boolean isSplit(DocHandlerContext docCtx) {
        return TextMatcher.anyMatches(
                configuration.getEmbeddedConfig().getSplitContentTypes(),
                docCtx.contentType().toBaseTypeString());
    }

    protected Parser createRecursiveParser(
            DocHandlerContext docCtx, Writer output, List<Doc> embeddedDocs) {
        // if the current file (container) matches, we extract (split)
        // its embedded documents (else, we merge).
        if (isSplit(docCtx)) {
            return new RecursiveEmbeddedSplitter(
                    tikaParser,
                    docCtx,
                    embeddedDocs,
                    configuration.getEmbeddedConfig());
        }
        return new RecursiveEmbeddedMerger(
                tikaParser,
                output,
                docCtx,
                configuration.getEmbeddedConfig());
    }

    /**
     * Applies the Grobid configuration. When Grobid is disabled (the default),
     * the JournalParser (which relies on a Grobid service) is excluded from
     * the parsers by {@link DefTikaConfigurer}, preventing network calls to
     * a Grobid service that is not running.
     */
    private void applyGrobidConfig() {
        var grobidCfg = configuration.getGrobidConfig();
        if (grobidCfg.isEnabled()) {
            var serviceUrl = grobidCfg.getServiceUrl();
            if (serviceUrl != null && !serviceUrl.isBlank()) {
                System.setProperty("grobid.server.url", serviceUrl);
            }
            LOG.info("Grobid parsing is enabled (service URL: {}).",
                    serviceUrl);
        }
    }

    private void fixTikaInitWarning() {
        // A check for Tesseract OCR parser is done the first time a Tika
        // parser is used. We remove this check since we manage Tesseract OCR
        // via Importer config only.
        disableTikaWarningFlag(
                TIKA_TESSERACT_OCR_PARSER,
                "HAS_WARNED",
                "Could not disable invalid Tesseract OCR warning. "
                        + "If you see such warning, you can ignore.");
    }

    private void disableTikaWarningFlag(
            String className, String fieldName, String failureMessage) {
        try {
            Class<?> clazz = Class.forName(className);
            if (FieldUtils.getField(clazz, fieldName, true) == null) {
                return;
            }
            FieldUtils.writeStaticField(clazz, fieldName, true, true);
        } catch (ClassNotFoundException e) {
            LOG.debug("Tika class not found: {}", className);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            LOG.warn(failureMessage);
        }
    }
}
