/* Copyright 2014-2026 Norconex Inc.
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
package com.norconex.importer.doc;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.detect.Detector;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.mime.MimeTypes;
import org.apache.tika.mime.MimeTypesFactory;

import com.norconex.commons.lang.file.ContentType;

import lombok.extern.slf4j.Slf4j;

/**
 * Main class to detect all content types.  This class is thread-safe.
 */
@Slf4j
public final class ContentTypeDetector {

    private static final Pattern EXTENSION_PATTERN =
            Pattern.compile("^.*(\\.[A-z0-9]+).*");
    private static final MimeTypes CUSTOM_MIME_TYPES;
    private static final Detector DETECTOR;
    static {
        CUSTOM_MIME_TYPES = createCustomMimeTypes();
        DETECTOR = new DefaultDetector(CUSTOM_MIME_TYPES);
    }

    private static MimeTypes createCustomMimeTypes() {
        var cl = ContentTypeDetector.class.getClassLoader();
        try {
            var urls = new ArrayList<URL>();

            // Tika core MIME types
            var coreUrl = cl.getResource(
                    "org/apache/tika/mime/tika-mimetypes.xml");
            if (coreUrl == null) {
                LOG.warn(
                        "Tika core mime types not found; falling back to defaults.");
                return MimeTypes.getDefaultMimeTypes();
            }
            urls.add(coreUrl);

            // Norconex custom types bundled with the importer
            var norconexUrl = cl.getResource(
                    "org/apache/tika/mime/custom-mimetypes.xml");
            if (norconexUrl != null) {
                urls.add(norconexUrl);
            }

            // User-provided drop-in: any "custom-mimetypes.xml" at the
            // classpath root in any JAR (Tika's documented extension point)
            urls.addAll(Collections.list(
                    cl.getResources("custom-mimetypes.xml")));

            // User-provided external file via system property
            var sysPropPath = System.getProperty("tika.custom-mimetypes");
            if (sysPropPath != null) {
                var externalFile = new File(sysPropPath);
                if (externalFile.exists()) {
                    urls.add(externalFile.toURI().toURL());
                } else {
                    LOG.warn(
                            "Custom MIME types file not found: {}",
                            sysPropPath);
                }
            }

            return MimeTypesFactory.create(urls.toArray(new URL[0]));
        } catch (IOException | MimeTypeException e) {
            LOG.warn(
                    "Could not load custom mime types; falling back to defaults.",
                    e);
            return MimeTypes.getDefaultMimeTypes();
        }
    }

    /**
     * Returns the custom MIME type detector used for content type detection.
     * This detector is pre-configured with Norconex custom MIME types
     * (e.g., application/vnd.xfdl) and can be used to configure
     * Tika parsers to recognise the same types.
     * @return the custom Tika detector
     */
    public static Detector getDetector() {
        return DETECTOR;
    }

    private ContentTypeDetector() {
    }

    /**
     * Detects the content type of the given file.
     * @param file file on which to detect content type
     * @return the detected content type
     * @throws IOException problem detecting content type
     */
    public static ContentType detect(File file) throws IOException {
        return detect(file, file.getName());
    }

    /**
     * Detects the content type of the given file.
     * @param file file on which to detect content type
     * @param fileName a file name which can help influence detection
     * @return the detected content type
     * @throws IOException problem detecting content type
     */
    public static ContentType detect(
            File file, String fileName) throws IOException {
        var safeFileName = fileName;
        if (StringUtils.isBlank(safeFileName)) {
            safeFileName = file.getName();
        }
        return doDetect(TikaInputStream.get(file.toPath()), safeFileName);
    }

    /**
     * Detects the content type from the given input stream.
     * @param content the content on which to detect content type
     * @return the detected content type
     * @throws IOException problem detecting content type
     */
    public static ContentType detect(InputStream content) throws IOException {
        return doDetect(content, null);
    }

    /**
     * Detects the content type from the given input stream.
     * @param content the content on which to detect content type
     * @param fileName a file name which can help influence detection
     * @return the detected content type
     * @throws IOException problem detecting content type
     */
    public static ContentType detect(InputStream content, String fileName)
            throws IOException {
        return doDetect(content, fileName);
    }

    //NOTE: important not to close the stream here. Will be handled by caller
    // if need be.
    private static ContentType doDetect(
            InputStream is, String fileName) throws IOException {
        var tikaStream = TikaInputStream.get(is);
        var meta = new Metadata();
        if (StringUtils.isNotBlank(fileName)) {
            var extension =
                    EXTENSION_PATTERN.matcher(fileName).replaceFirst("$1");
            meta.set(
                    TikaCoreProperties.RESOURCE_NAME_KEY,
                    "file:///detect" + extension);
        }
        var media = DETECTOR.detect(tikaStream, meta,
                new org.apache.tika.parser.ParseContext());

        LOG.debug("Detected \"{}\" content-type for file: {}", media, fileName);
        return ContentType.valueOf(media.toString());
    }
}
