/* Copyright 2015-2024 Norconex Inc.
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

import java.nio.file.Path;

import com.norconex.commons.lang.text.TextMatcher;

import lombok.Data;
import lombok.experimental.Accessors;
import lombok.experimental.FieldNameConstants;

/**
 * <p>
 * OCR configuration details. OCR relies the open-source
 * <a href="https://github.com/tesseract-ocr/tesseract">Tesseract OCR</a>
 * product to be already installed on your system.
 * </p>
 * <p>You can configure this parser to use the
 * <b><a href="https://code.google.com/p/tesseract-ocr/">Tesseract</a></b>
 * open-source OCR application to extract text out of images
 * or documents containing embedded images (e.g. PDF).  Supported image
 * formats are TIFF, PNG, JPEG, GIF, and BMP.</p>
 *
 * <p>To enable this feature, you must
 * first download and install a copy of Tesseract appropriate for
 * your platform (supported are Linux, Windows, Mac and other platforms).
 * It will only be activated once you configure the path to its install
 * location.
 * Default language detection is for English. To support additional or
 * different languages,
 * you can provide a list of three-letter ISO-639-2 language codes supported
 * by Tesseract (separated with + sign).
 * These languages must be part of your Tesseract installation.
 * You can <a href="https://code.google.com/p/tesseract-ocr/downloads/list">
 * download additional languages</a> form the Tesseract web site.</p>
 *
 * <p>When enabled, OCR is attempted on all supported image formats.  To
 * limit OCR to a subset of document content types, configure the corresponding
 * content-types (e.g. application/pdf, image/tiff, image/png, etc.).</p>
 *
 * <h2>Tesseract Parameters</h2>
 * <p>
 * Unless disabled, Tesseract is detected and used by default.
 * You can
 * There are several extra parameters one can set to configure
 * Tesseract. You can find a listing
 * <a href="https://cwiki.apache.org/confluence/display/tika/TikaOCR">here</a>.
 * </p>
 *
 * @see <a href="https://crawler.norconex.com/docs/reference/importer/OcrConfig">
 *      OcrConfig configuration reference</a>
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
@FieldNameConstants
public class OcrConfig {

    //TODO extend Tika TesseractOCRConfig instead?
    // Nahh... it does not have all fields, plus not chaining setters.
    // instead use BeanUtil to clone it? Or use reflection
    // to map it to config? Or do it manually.

    private boolean disabled;
    private final TextMatcher contentTypeMatcher = new TextMatcher();

    // Straight from Tika Tesseract config:
    //     https://cwiki.apache.org/confluence/display/TIKA/TikaOCR
    private Boolean applyRotation;
    private String colorSpace;
    private Integer density;
    private Integer depth;
    private Boolean enableImagePreprocessing;
    private String filter;
    private Path imageMagickPath;
    private String language;
    private Long maxFileSizeToOcr;
    private Long minFileSizeToOcr;
    private String pageSegMode;
    private String pageSeparator;
    private Boolean preserveInterwordSpacing;
    private Integer resize;
    private Boolean skipOcr;
    private Path tessdataPath;
    private Path tesseractPath;
    private Integer timeoutSeconds;

    /**
     * The matcher of content types to apply OCR on.
     * @param contentTypeMatcher content type matcher
     * @return this
     */
    public OcrConfig setContentTypeMatcher(TextMatcher contentTypeMatcher) {
        this.contentTypeMatcher.copyFrom(contentTypeMatcher);
        return this;
    }

    //
    //
    //
    //    // below to refactor:
    //
    //
    ////    private boolean disabled;
    //
    //    /**
    //     * The Tesseract OCR engine full path to the executable file.
    //     * @param path executable path
    //     * @return path executable path
    //     */
    ////    private Path tesseractPath;
    ////    private Path tessdataPath;
    //
    //    private final List<TextMatcher> contentTypeMatchers = new ArrayList<>();
    //
    ////    private Path imageMagickPath;
    //
    //    @ToString.Exclude
    //    @EqualsAndHashCode.Exclude
    //    @NonNull
    //    private TesseractOCRConfig tesseractConfig = new TesseractOCRConfig();
    //
    //    /**
    //     * Text matchers restricting which content types to apply OCR.
    //     * @return content type matchers
    //     */
    //    public List<TextMatcher> getContentTypeMatchers() {
    //        return Collections.unmodifiableList(contentTypeMatchers);
    //    }
    //    /**
    //     * Text matchers restricting which content types to apply OCR.
    //     * @param matchers content type matchers
    //     */
    //    public void setContentTypeMatchers(List<TextMatcher> matchers) {
    //        CollectionUtil.setAll(contentTypeMatchers, matchers);
    //    }
    //
    //    @EqualsAndHashCode.Include
    //    @ToString.Include
    //    private String tesseractConfigToString() {
    //        return new ReflectionToStringBuilder(
    //                tesseractConfig,
    //                ToStringStyle.NO_CLASS_NAME_STYLE)
    //            .setExcludeFieldNames("userConfigured")
    //            .toString();
    //    }
    //
    //    @Override
    //    public void loadFromXML(XML xml) {
    //        setDisabled(xml.getBoolean("@disabled"));
    //        setTesseractPath(xml.getPath(Fields.tesseractPath, tesseractPath));
    //        setTessdataPath(xml.getPath(Fields.tessdataPath, tessdataPath));
    //        setContentTypeMatchers(xml.getXMLList("contentTypes/matcher").stream()
    //            .map(x -> {
    //                var tm = new TextMatcher();
    //                tm.loadFromXML(x);
    //                return tm;
    //            })
    //            .toList());
    //        setImageMagickPath(
    //                xml.getPath(Fields.imageMagickPath, getImageMagickPath()));
    //
    //        var t = tesseractConfig;
    //        t.setApplyRotation(
    //                xml.getBoolean("applyRotation", t.isApplyRotation()));
    //        t.setColorspace(xml.getString("colorSpace", t.getColorspace()));
    //        t.setDensity(xml.getInteger("density", t.getDensity()));
    //        t.setDepth(xml.getInteger("depth", t.getDepth()));
    //        t.setEnableImagePreprocessing(xml.getBoolean(
    //                "enableImagePreprocessing", t.isEnableImagePreprocessing()));
    //        t.setFilter(xml.getString("filter", t.getFilter()));
    //        t.setLanguage(xml.getString("language", t.getLanguage()));
    //        t.setMaxFileSizeToOcr(
    //                xml.getLong("maxFileSizeToOcr", t.getMaxFileSizeToOcr()));
    //        t.setMinFileSizeToOcr(
    //                xml.getLong("minFileSizeToOcr", t.getMinFileSizeToOcr()));
    //        t.setPageSegMode(
    //                xml.getString("pageSegMode", t.getPageSegMode()));
    //        // TesseractOCRConfig defaults to "" but does not want ""
    //        var pageSep = xml.getString("pageSeparator");
    //        if (StringUtils.isNotBlank(pageSep)) {
    //            t.setPageSeparator(
    //                    xml.getString("pageSeparator", t.getPageSeparator()));
    //        }
    //        t.setPreserveInterwordSpacing(xml.getBoolean(
    //                "preserveInterwordSpacing", t.isPreserveInterwordSpacing()));
    //        t.setResize(xml.getInteger("resize", t.getResize()));
    //        t.setTimeoutSeconds(
    //                xml.getInteger("timeoutSeconds", t.getTimeoutSeconds()));
    //    }
    //
    //    @Override
    //    public void saveToXML(XML xml) {
    //        xml.setAttribute("disabled", disabled);
    //        xml.addElement(Fields.tesseractPath, tesseractPath);
    //        xml.addElement(Fields.tessdataPath, tessdataPath);
    //        xml.addElementList("contentTypes", "matcher", contentTypeMatchers);
    //        xml.addElement(Fields.imageMagickPath, imageMagickPath);
    //
    //        var t = tesseractConfig;
    //        xml.addElement("applyRotation", t.isApplyRotation());
    //        xml.addElement("colorSpace", t.getColorspace());
    //        xml.addElement("density", t.getDensity());
    //        xml.addElement("depth", t.getDepth());
    //        xml.addElement(
    //                "enableImagePreprocessing", t.isEnableImagePreprocessing());
    //        xml.addElement("filter", t.getFilter());
    //        xml.addElement("language", t.getLanguage());
    //        xml.addElement("maxFileSizeToOcr", t.getMaxFileSizeToOcr());
    //        xml.addElement("minFileSizeToOcr", t.getMinFileSizeToOcr());
    //        xml.addElement("pageSegMode", t.getPageSegMode());
    //        xml.addElement("pageSeparator", t.getPageSeparator());
    //        xml.addElement(
    //                "preserveInterwordSpacing", t.isPreserveInterwordSpacing());
    //        xml.addElement("resize", t.getResize());
    //        xml.addElement("timeoutSeconds", t.getTimeoutSeconds());
    //    }
}
