/* Copyright 2023-2024 Norconex Inc.
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.tika.config.loader.TikaJsonConfig;
import org.apache.tika.config.loader.TikaLoader;
import org.apache.tika.exception.TikaException;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.Parser;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
final class DefTikaConfigurer {

    private static final String TESSERACT_PARSER_NAME = "tesseract-ocr-parser";
    private static final String JOURNAL_PARSER_NAME = "journal-parser";

    private static final String TESSERACT_CLASS =
            "org.apache.tika.parser.ocr.TesseractOCRParser";
    private static final String JOURNAL_CLASS =
            "org.apache.tika.parser.journal.JournalParser";

    private DefTikaConfigurer() {
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false,
                    DefTikaConfigurer.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    // Useful: https://tika.apache.org/4.x/configuring.html
    // and: https://cwiki.apache.org/confluence/display/tika/TikaOCR
    static AutoDetectParser configure(DefaultParserConfig config)
            throws IOException {
        try {
            var ocr = config.getOcrConfig();

            // Exclude parsers from the default parser composite that must
            // not be auto-initialized with their default settings:
            // - Tesseract OCR, when enabled (re-added below with the
            //   supplied settings).
            // - Journal parser (relies on a Grobid service), unless Grobid
            //   is explicitly enabled.
            List<String> excludes = new ArrayList<>();
            var withOcr = !ocr.isDisabled() && isPresent(TESSERACT_CLASS);
            if (!ocr.isDisabled() && !withOcr) {
                LOG.warn("OCR is enabled but the Tika Tesseract OCR module is "
                        + "not on the classpath. OCR will not be performed.");
            }
            if (withOcr) {
                excludes.add(TESSERACT_PARSER_NAME);
            }
            // Names that are not registered (module absent) make Tika fail.
            if (!config.getGrobidConfig().isEnabled()
                    && isPresent(JOURNAL_CLASS)) {
                excludes.add(JOURNAL_PARSER_NAME);
            }

            var mapper = new ObjectMapper();
            var defaultParser = mapper.createObjectNode();
            defaultParser.putArray("exclude").addAll(
                    excludes.stream()
                            .map(mapper.getNodeFactory()::textNode)
                            .toList());
            var root = mapper.createObjectNode();
            root.putArray("parsers")
                    .addObject().set("default-parser", defaultParser);

            var tikaConfig = TikaJsonConfig.load(
                    new ByteArrayInputStream(
                            mapper.writeValueAsBytes(root)));
            var loader = TikaLoader.load(
                    tikaConfig, DefTikaConfigurer.class.getClassLoader());

            List<Parser> parsers = new ArrayList<>();
            parsers.add(loader.loadParsers());
            if (withOcr) {
                parsers.add(TesseractParserFactory.create(ocr));
            }
            // XFDL Parser
            // https://issues.apache.org/jira/browse/TIKA-2222
            parsers.add(new XfdlTikaParser());
            return new AutoDetectParser(parsers.toArray(Parser[]::new));
        } catch (TikaException e) {
            throw new IOException("Could not configure Tika.", e);
        }
    }

    /**
     * Kept in its own class so the Tesseract parser (an optional Tika module)
     * is only loaded when OCR is enabled.
     */
    static final class TesseractParserFactory {
        private TesseractParserFactory() {
        }

        static Parser create(OcrConfig ocr) throws TikaException {
            var cfg = new org.apache.tika.parser.ocr.TesseractOCRConfig();
            set(ocr.getApplyRotation(), cfg::setApplyRotation);
            set(ocr.getColorSpace(), cfg::setColorspace);
            set(ocr.getDensity(), cfg::setDensity);
            set(ocr.getDepth(), cfg::setDepth);
            set(ocr.getEnableImagePreprocessing(),
                    cfg::setEnableImagePreprocessing);
            set(ocr.getFilter(), cfg::setFilter);
            set(ocr.getLanguage(), cfg::setLanguage);
            set(ocr.getMaxFileSizeToOcr(), cfg::setMaxFileSizeToOcr);
            set(ocr.getMinFileSizeToOcr(), cfg::setMinFileSizeToOcr);
            set(ocr.getPageSegMode(), cfg::setPageSegMode);
            set(ocr.getPageSeparator(), cfg::setPageSeparator);
            set(ocr.getPreserveInterwordSpacing(),
                    cfg::setPreserveInterwordSpacing);
            set(ocr.getResize(), cfg::setResize);
            set(ocr.getSkipOcr(), cfg::setSkipOcr);
            set(ocr.getTimeoutSeconds(), s -> cfg.setTimeoutMillis(s * 1000L));
            if (ocr.getImageMagickPath() != null) {
                cfg.setImageMagickPath(abs(ocr.getImageMagickPath()));
            }
            if (ocr.getTessdataPath() != null) {
                cfg.setTessdataPath(abs(ocr.getTessdataPath()));
            }
            if (ocr.getTesseractPath() != null) {
                cfg.setTesseractPath(abs(ocr.getTesseractPath()));
            }
            return new org.apache.tika.parser.ocr.TesseractOCRParser(cfg);
        }

        private static String abs(java.nio.file.Path p) {
            return p.toAbsolutePath().toString();
        }

        private static <T> void set(T value, java.util.function.Consumer<T> c) {
            Optional.ofNullable(value).ifPresent(c);
        }
    }
}
