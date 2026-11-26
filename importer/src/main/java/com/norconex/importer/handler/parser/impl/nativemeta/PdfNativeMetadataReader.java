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
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.PDF;
import org.apache.tika.metadata.PagedText;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.xmp.XmpExtractor;
import org.apache.tika.parser.xmp.XmpProperty;
import org.apache.tika.parser.xmp.XmpSaxFlattener;

import com.norconex.commons.lang.file.ContentType;

/**
 * Reads what a PDF says about itself: the entries of its document
 * information dictionary ({@code Title}, {@code Author}, {@code Producer},
 * {@code CreationDate}, and any custom entry), under their own names, and
 * the properties of its XMP packet, under the names the packet gave them
 * ({@code dc:creator}, {@code xmp:CreatorTool}, {@code pdf:Producer}, ...).
 * <p>
 * Only the file structure and these two places are read, not the pages.
 * Dates are kept as written in the file ({@code D:20150318211559Z}).
 * </p>
 * <p>
 * The PDF parser merges the two sources into properties of its own, so what
 * it wrote for them ({@code pdf:docinfo:*}, {@code xmp-raw:*}, {@code dc:*}
 * and the like) is replaced by what was read. What the parser works out
 * itself ({@code pdf:encrypted}, page counts, access permissions, ...)
 * is left alone.
 * </p>
 */
final class PdfNativeMetadataReader implements NativeMetadataReader {

    private static final Set<String> TYPES = Set.of("application/pdf");

    private static final Pattern TRAILING_INDEX =
            Pattern.compile("\\[\\d+\\]$");

    /** An embedded base64 thumbnail: not a field worth carrying. */
    private static final String THUMBNAIL_PATH = "xmpGImg:image";

    /**
     * Written by the parser from the file itself, whatever the XMP says,
     * so they are not what the XMP said.
     */
    private static final Set<String> TIKA_OWN = Set.of(
            PDF.PDF_VERSION.getName(), PagedText.N_PAGES.getName());

    /** What the parser derives from the information dictionary. */
    private static final List<String> TIKA_FROM_INFO = List.of(
            TikaCoreProperties.TITLE.getName(),
            TikaCoreProperties.CREATOR.getName(),
            TikaCoreProperties.CREATOR_TOOL.getName(),
            TikaCoreProperties.SUBJECT.getName(),
            TikaCoreProperties.CREATED.getName(),
            TikaCoreProperties.MODIFIED.getName(),
            PDF.PRODUCER.getName(),
            PDF.TRAPPED.getName());

    @Override
    public boolean supports(ContentType contentType) {
        return TYPES.contains(contentType.toBaseTypeString());
    }

    @Override
    public NativeFields read(TikaInputStream in, String charset)
            throws IOException {
        var found = NativeFields.newMap();
        Metadata derivedFromXmp = new Metadata();

        try (var pdf = Loader.loadPDF(in.getPath().toFile())) {
            readInfo(pdf.getDocumentInformation().getCOSObject()
                    .entrySet(), found);

            var xmp = pdf.getDocumentCatalog().getMetadata();
            if (xmp != null) {
                byte[] packet;
                try (var is = xmp.exportXMPMetadata()) {
                    packet = is.readAllBytes();
                }
                readXmp(packet, found, derivedFromXmp);
            }
        } catch (InvalidPasswordException e) {
            // not readable without the password: the parser says so itself
            return NativeFields.none();
        }
        if (found.isEmpty()) {
            return NativeFields.none();
        }
        return new NativeFields(found, tika -> supersede(tika, derivedFromXmp));
    }

    private static void readInfo(
            Set<Map.Entry<COSName, COSBase>> entries,
            Map<String, List<String>> found) {
        for (var entry : entries) {
            var value = text(entry.getValue());
            if (value != null && !value.isBlank()) {
                NativeFields.add(found, entry.getKey().getName(), value);
            }
        }
    }

    private static String text(COSBase base) {
        if (base instanceof COSObject obj) {
            base = obj.getObject();
        }
        if (base instanceof COSString s) {
            return s.getString();
        }
        if (base instanceof COSName n) {
            return n.getName();
        }
        if (base instanceof COSNumber n) {
            return n.toString();
        }
        if (base instanceof COSBoolean b) {
            return Boolean.toString(b.getValue());
        }
        return null;
    }

    private static void readXmp(
            byte[] packet,
            Map<String, List<String>> found,
            Metadata derivedFromXmp) {
        try {
            for (XmpProperty p : new XmpSaxFlattener().flatten(packet)) {
                // not the xml:lang qualifiers, empty values or thumbnail
                var name = TRAILING_INDEX.matcher(p.path).replaceFirst("");
                if (!p.path.endsWith("xml:lang") && p.value != null
                        && !p.value.isBlank()
                        && !name.equals(THUMBNAIL_PATH)) {
                    NativeFields.add(found, name, p.value);
                }
            }
            // what the parser makes of the same packet, to take it out
            new XmpExtractor().extract(packet, derivedFromXmp);
        } catch (Exception e) {
            // A damaged packet is the parser's to report.
            for (var n : derivedFromXmp.names()) {
                derivedFromXmp.remove(n);
            }
        }
    }

    private static void supersede(Metadata tika, Metadata derivedFromXmp) {
        for (var name : tika.names()) {
            if (name.startsWith("pdf:docinfo:")
                    || name.startsWith("xmp-raw:")) {
                tika.remove(name);
            }
        }
        TIKA_FROM_INFO.forEach(tika::remove);
        for (var name : derivedFromXmp.names()) {
            if (!TIKA_OWN.contains(name)) {
                tika.remove(name);
            }
        }
    }
}
