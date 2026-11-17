/* Copyright 2016-2026 Norconex Inc.
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

import static com.norconex.importer.TestUtil.resourceAsFile;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class ImageParserTest {

    @TempDir
    static Path folder;

    @Test
    void testWEBP() throws Exception {
        testParsing("image/webp", "webp");
    }

    @Test
    void testBMP() throws Exception {
        testParsing("image/bmp", "bmp");
    }

    @Test
    void testGIF() throws Exception {
        testParsing("image/gif", "gif");
    }

    @Test
    void testJPG() throws Exception {
        testParsing("image/jpeg", "jpg");
    }

    @Test
    void testJPG_XMP() throws Exception {
        // JPEG with XMP metadata.  Can be dealt with, with a tool such
        // as http://www.exiv2.org
        // Currently parsed by Tika using Jempbox
        ParseAssertions.assertThat(
                resourceAsFile(folder, "/parser/image/importer-xmp.jpg"))
                .hasContentType("image/jpeg")
                .hasContentFamily("Image")
                .hasExtension("jpg")
                .hasMetaValue("dc:subject", "XMP Parsing");
    }

    @Test
    void testPNG() throws Exception {
        testParsing("image/png", "png");
    }

    @Test
    void testPSD() throws Exception {
        ParseAssertions.assertThat(
                resourceAsFile(folder, "/parser/image/importer.psd"))
                .hasContentType("image/vnd.adobe.photoshop")
                .hasContentFamily("Image")
                .hasExtension("psd");
    }

    @Test
    void testTIF() throws Exception {
        ParseAssertions.assertThat(
                resourceAsFile(folder, "/parser/image/importer.tif"))
                .hasContentType("image/tiff")
                .hasContentFamily("Image")
                .hasExtension("tiff");
    }

    @Test
    void testJBIG2() throws Exception {
        ParseAssertions.assertThat(
                resourceAsFile(folder, "/parser/image/importer.jb2"))
                .hasContentType("image/x-jbig2")
                .hasContentFamily("Image")
                .hasExtension("jb2")
                .hasMetaValue("img:width", "125")
                .hasMetaValue("img:height", "16");
    }

    private void testParsing(String contentType, String extension)
            throws Exception {
        ParseAssertions.assertThat(
                resourceAsFile(folder, "/parser/image/importer." + extension))
                .hasContentType(contentType)
                .hasContentFamily("Image")
                .hasExtension(extension);
    }
}
