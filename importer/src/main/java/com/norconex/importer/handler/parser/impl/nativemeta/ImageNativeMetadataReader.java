/* Copyright 2026 Norconex Inc.
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
package com.norconex.importer.handler.parser.impl.nativemeta;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.xmp.XmpProperty;
import org.apache.tika.parser.xmp.XmpSaxFlattener;

import com.adobe.internal.xmp.XMPException;
import com.adobe.internal.xmp.XMPMetaFactory;
import com.adobe.internal.xmp.options.SerializeOptions;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.file.FileSystemDirectory;
import com.drew.metadata.xmp.XmpDirectory;
import com.norconex.commons.lang.file.ContentType;

/**
 * Reads the embedded metadata of a picture (Exif, IPTC, XMP, ICC, and the
 * format's own headers). Exif, IPTC and format entries are named after
 * their directory and tag as the reader names them, such as
 * {@code Exif IFD0:Software} or {@code JPEG:Image Height}, with the value
 * as that reader describes it. XMP properties are named as the packet
 * wrote them ({@code dc:creator}, {@code xmp:CreatorTool}, ...).
 * <p>
 * The parser's version of the same entries (the {@code img:} prefix, the
 * {@code tiff:} and {@code exif:} mappings, the {@code dc:} values taken
 * from the XMP) is replaced by what was read. The file's name, size and
 * date are not part of the picture and are not added.
 * </p>
 */
final class ImageNativeMetadataReader implements NativeMetadataReader {

    private static final Set<String> TYPES = Set.of(
            "image/jpeg", "image/tiff", "image/png", "image/gif",
            "image/bmp", "image/webp", "image/vnd.adobe.photoshop",
            "image/heic", "image/heif", "image/x-ms-bmp");

    private static final Pattern TRAILING_INDEX =
            Pattern.compile("\\[\\d+\\]$");

    private static final List<String> TIKA_PREFIXES =
            List.of("img:", "tiff:", "exif:", "xmp:", "dc:", "dcterms:",
                    "geo:", "xmpRights:", "photoshop:", "xmpMM:", "xmpDM:");

    @Override
    public boolean supports(ContentType contentType) {
        return TYPES.contains(contentType.toBaseTypeString());
    }

    @Override
    public NativeFields read(TikaInputStream in, String charset)
            throws IOException {
        com.drew.metadata.Metadata meta;
        try {
            meta = ImageMetadataReader.readMetadata(in.getPath().toFile());
        } catch (ImageProcessingException e) {
            return NativeFields.none();
        }
        var found = NativeFields.newMap();
        for (var dir : meta.getDirectories()) {
            if (dir instanceof XmpDirectory xmp) {
                readXmp(xmp, found);
            } else if (!(dir instanceof FileSystemDirectory)
                    && !(dir instanceof com.drew.metadata.file.FileTypeDirectory)) {
                readTags(dir, found);
            }
        }
        return found.isEmpty()
                ? NativeFields.none()
                : new NativeFields(found, ImageNativeMetadataReader::supersede);
    }

    private static void readTags(
            com.drew.metadata.Directory dir,
            java.util.Map<String, List<String>> found) {
        for (var tag : dir.getTags()) {
            var description = tag.getDescription();
            if (description != null && !description.isBlank()) {
                NativeFields.add(found, dir.getName() + ":" + tag.getTagName(),
                        description.trim());
            }
        }
    }

    private static void readXmp(
            XmpDirectory xmp, java.util.Map<String, List<String>> found) {
        var meta = xmp.getXMPMeta();
        if (meta == null) {
            return;
        }
        try {
            var packet = XMPMetaFactory.serializeToBuffer(
                    meta, new SerializeOptions());
            for (XmpProperty p : new XmpSaxFlattener().flatten(packet)) {
                if (p.path.endsWith("xml:lang") || p.value == null
                        || p.value.isBlank()) {
                    continue;
                }
                var name = TRAILING_INDEX.matcher(p.path).replaceFirst("");
                if (!name.equals("xmpGImg:image")) {
                    NativeFields.add(found, name, p.value);
                }
            }
        } catch (XMPException | IOException | RuntimeException
                | org.apache.tika.exception.TikaException
                | org.xml.sax.SAXException e) {
            // a damaged packet is the parser's to report
        }
    }

    private static void supersede(Metadata tika) {
        for (var name : tika.names()) {
            if (TIKA_PREFIXES.stream().anyMatch(name::startsWith)) {
                tika.remove(name);
            }
        }
    }
}
