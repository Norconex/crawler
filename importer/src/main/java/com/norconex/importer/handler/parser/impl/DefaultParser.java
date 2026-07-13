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
import org.apache.tika.exception.ZeroByteFileException;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.CompositeParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.ParserDecorator;
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
    private AutoDetectParser tikaParser;
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private AtomicBoolean initialized = new AtomicBoolean();

    private static final String TIKA_TESSERACT_OCR_PARSER =
            "org.apache.tika.parser.ocr.TesseractOCRParser";
    private static final String TIKA_SQLITE_PARSER =
            "org.apache.tika.parser.jdbc.SQLite3Parser";
    private static final String GROBID_REST_PARSER =
            "org.apache.tika.parser.journal.GrobidRESTParser";
    private static final String JOURNAL_PARSER =
            "org.apache.tika.parser.journal.JournalParser";

    @Override
    public void init() throws IOException {

        fixTikaInitWarning();
        tikaParser = new AutoDetectParser(
                DefTikaConfigurer.configure(configuration));
        // Use the same custom MIME-type detector as ContentTypeDetector so
        // that types like application/vnd.xfdl are correctly routed to their
        // registered parsers (e.g. XfdlTikaParser).
        tikaParser.setDetector(ContentTypeDetector.getDetector());
        applyGrobidConfig();
        initialized.set(true);
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
                pdfConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
            }
            pdfConfig.setSuppressDuplicateOverlappingText(true);
            context.set(PDFParserConfig.class, pdfConfig);

            recursiveParser.parse(
                    input,
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
        return true;
    }

    protected Parser createRecursiveParser(
            DocHandlerContext docCtx, Writer output, List<Doc> embeddedDocs) {
        // if the current file (container) matches, we extract (split)
        // its embedded documents (else, we merge).
        if (TextMatcher.anyMatches(
                configuration.getEmbeddedConfig().getSplitContentTypes(),
                docCtx.contentType().toBaseTypeString())) {
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
     * the JournalParser (which wraps GrobidRESTParser internally) is removed
     * from the AutoDetectParser's internal parser list via reflection to
     * prevent network calls to a Grobid service that is not running.
     */
    private void applyGrobidConfig() {
        var grobidCfg = configuration.getGrobidConfig();
        if (!grobidCfg.isEnabled()) {
            boolean removed = removeGrobidParsersFromList(
                    (CompositeParser) tikaParser);
            if (removed) {
                LOG.debug("Grobid parsing is disabled. "
                        + "JournalParser has been removed from the Tika "
                        + "parser chain. Set GrobidConfig enabled=true "
                        + "to activate it.");
            }
        } else {
            var serviceUrl = grobidCfg.getServiceUrl();
            if (serviceUrl != null && !serviceUrl.isBlank()) {
                System.setProperty("grobid.server.url", serviceUrl);
            }
            LOG.info("Grobid parsing is enabled (service URL: {}).",
                    serviceUrl);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean removeGrobidParsersFromList(CompositeParser cp) {
        try {
            var field = CompositeParser.class.getDeclaredField("parsers");
            field.setAccessible(true);
            List<Parser> list = (List<Parser>) field.get(cp);
            List<Parser> mutable = new ArrayList<>(list);
            boolean removed = mutable.removeIf(p -> {
                Parser unwrapped = unwrapDecorator(p);
                if (isGrobidParser(unwrapped)) {
                    return true;
                }
                if (unwrapped instanceof CompositeParser) {
                    removeGrobidParsersFromList((CompositeParser) unwrapped);
                }
                return false;
            });
            if (removed) {
                field.set(cp, mutable);
            }
            return removed;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOG.warn("Could not disable Grobid parsers via reflection; "
                    + "Grobid-related warnings may appear.", e);
            return false;
        }
    }

    private static Parser unwrapDecorator(Parser p) {
        while (p instanceof ParserDecorator) {
            p = ((ParserDecorator) p).getWrappedParser();
        }
        return p;
    }

    private boolean isGrobidParser(Parser p) {
        if (p == null) {
            return false;
        }
        var name = p.getClass().getName();
        return GROBID_REST_PARSER.equals(name) || JOURNAL_PARSER.equals(name);
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
        // A check for SQLite is also done and we do not want it.
        disableTikaWarningFlag(
                TIKA_SQLITE_PARSER,
                "HAS_WARNED",
                "Could not disable \"sqlite-jdbc\" warning. "
                        + "If you see such warning, you can ignore.");
    }

    private void disableTikaWarningFlag(
            String className, String fieldName, String failureMessage) {
        try {
            Class<?> clazz = Class.forName(className);
            FieldUtils.writeStaticField(clazz, fieldName, true, true);
        } catch (ClassNotFoundException e) {
            LOG.debug("Tika class not found: {}", className);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            LOG.warn(failureMessage);
        }
    }
}
