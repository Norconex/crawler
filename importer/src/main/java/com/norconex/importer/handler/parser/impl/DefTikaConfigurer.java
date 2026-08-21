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

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.filter.FieldNameMappingFilter;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import org.apache.tika.parser.sentiment.SentimentAnalysisParser;
import org.w3c.dom.Element;

import com.norconex.commons.lang.xml.Xml;
import com.norconex.commons.lang.xml.XmlUtil;

final class DefTikaConfigurer {

    private DefTikaConfigurer() {
    }

    // Useful: https://tika.apache.org/2.7.0/configuring.html
    // and: https://cwiki.apache.org/confluence/display/tika/TikaOCR
    // and: https://cwiki.apache.org/confluence/display/TIKA/Metadata+Filters
    static TikaConfig configure(DefaultParserConfig config) throws IOException {
        try {
            var docBuilderfactory = XmlUtil.createDocumentBuilderFactory();
            docBuilderfactory.setNamespaceAware(true);
            var tikaXml = Xml.of("properties")
                    .setDocumentBuilderFactory(docBuilderfactory)
                    .create();

            tikaXml.addXML("""
                    <service-loader
                        loadErrorHandler="WARN"
                        initializableProblemHandler="IGNORE"/>
                    """);

            //TODO How to handle Tika 2.x changing original metadata
            // to different ones? Here we do title being an obvious one
            // people expects from HTML.
            // shall we do our own normalization above those standards?
            // Adopt and document tika normalization rules?

            //TODO why the following isn't working?
            tikaXml.addXML("""
                    <metadataFilters>
                      <metadataFilter class="%s">
                        <params>
                          <excludeUnmapped>false</excludeUnmapped>
                          <!--
                          <mappings>
                            <mapping from="dc:title" to="title"/>
                          </mappings>
                          -->
                        </params>
                      </metadataFilter>
                    </metadataFilters>
                    """.formatted(FieldNameMappingFilter.class.getName()));

            var parsersXml = tikaXml.addElement("parsers");
            var ocr = config.getOcrConfig();
            var sentiment = config.getSentimentConfig();

            // Exclude parsers from the default parser composite that must
            // not be auto-initialized with their default settings:
            // - Tesseract OCR, when explicitly configured (re-added below
            //   with the supplied settings).
            // - Sentiment analysis, always. Unlike most Tika parsers,
            //   SentimentAnalysisParser downloads a model from the network
            //   the moment it is initialized, regardless of whether it
            //   ever gets used. Left unexcluded, this network call happens
            //   for every document parser initialization (i.e., typically
            //   on startup), which can slow down or fail startup when the
            //   model URL is unreachable. It is re-added below, with our
            //   own configured settings, only when explicitly enabled.
            var excludesXml = new StringBuilder();
            if (!ocr.isDisabled()) {
                excludesXml.append("<parser-exclude class=\"%s\"/>"
                        .formatted(TesseractOCRParser.class.getName()));
            }
            excludesXml.append("<parser-exclude class=\"%s\"/>"
                    .formatted(SentimentAnalysisParser.class.getName()));
            parsersXml.addXML(
                    """
                            <parser class="%s">
                                %s
                            </parser>
                            """.formatted(
                            org.apache.tika.parser.DefaultParser.class
                                    .getName(),
                            excludesXml));

            // https://cwiki.apache.org/confluence/display/TIKA/TikaOCR
            if (!ocr.isDisabled()) {
                // Configure Tesseract OCR
                parsersXml.addXML(
                        new TesseractParserConfigBuilder()
                                .append("applyRotation", ocr.getApplyRotation())
                                .append("colorSpace", ocr.getColorSpace())
                                .append("density", ocr.getDensity())
                                .append("depth", ocr.getDepth())
                                .append(
                                        "enableImagePreprocessing",
                                        ocr.getEnableImagePreprocessing())
                                .append("filter", ocr.getFilter())
                                .append(
                                        "imageMagickPath",
                                        ocr.getImageMagickPath())
                                .append("language", ocr.getLanguage())
                                .append(
                                        "maxFileSizeToOcr",
                                        ocr.getMaxFileSizeToOcr())
                                .append(
                                        "minFileSizeToOcr",
                                        ocr.getMinFileSizeToOcr())
                                .append("pageSegMode", ocr.getPageSegMode())
                                .append("pageSeparator", ocr.getPageSeparator())
                                .append(
                                        "preserveInterwordSpacing",
                                        ocr.getPreserveInterwordSpacing())
                                .append("resize", ocr.getResize())
                                .append("skipOcr", ocr.isDisabled())
                                .append("tessdataPath", ocr.getTessdataPath())
                                .append("tesseractPath", ocr.getTesseractPath())
                                .append(
                                        "timeoutSeconds",
                                        ocr.getTimeoutSeconds())
                                .build());
            }

            // Configure sentiment analysis, only when explicitly enabled.
            if (sentiment.isEnabled()) {
                parsersXml.addXML(
                        """
                                <parser class="%s">
                                    <params>
                                        <param name="modelPath" type="string">%s</param>
                                    </params>
                                </parser>
                                """.formatted(
                                SentimentAnalysisParser.class.getName(),
                                StringUtils.defaultIfBlank(
                                        sentiment.getModelPath(),
                                        SentimentConfig.DEFAULT_MODEL_PATH)));
            }

            // XFDL Parser
            // https://issues.apache.org/jira/browse/TIKA-2222
            parsersXml.addXML(
                    """
                            <parser class="%s">
                                <mime>application/vnd.xfdl</mime>
                            </parser>
                            """
                            .formatted(XfdlTikaParser.class.getName()));

            return new TikaConfig((Element) tikaXml.getNode());
        } catch (TikaException e) {
            throw new IOException("Could not configure Tika.", e);
        }
    }

    static class TesseractParserConfigBuilder {
        private final Xml parser = new Xml("parser")
                .setAttribute("class", TesseractOCRParser.class.getName());
        private final Xml params = parser.addElement("params");

        TesseractParserConfigBuilder append(String name, Boolean value) {
            if (value != null) {
                return append(name, "bool", Boolean.toString(value));
            }
            return this;
        }

        TesseractParserConfigBuilder append(String name, String value) {
            if (value != null) {
                return append(name, "string", value);
            }
            return this;
        }

        TesseractParserConfigBuilder append(String name, Integer value) {
            if (value != null) {
                return append(name, "int", Integer.toString(value));
            }
            return this;
        }

        TesseractParserConfigBuilder append(String name, Long value) {
            if (value != null) {
                return append(name, "long", Long.toString(value));
            }
            return this;
        }

        TesseractParserConfigBuilder append(String name, Path value) {
            return append(
                    name, "string", Optional.ofNullable(value)
                            .map(Path::toAbsolutePath)
                            .map(Path::toString).orElse(null));
        }

        private TesseractParserConfigBuilder append(
                String name, String type, String value) {
            if (StringUtils.isNotBlank(value)) {
                params.addElement("param")
                        .setAttribute("name", name)
                        .setAttribute("type", type)
                        .setTextContent(value);
            }
            return this;
        }

        public Xml build() {
            return parser;
        }
    }
}
