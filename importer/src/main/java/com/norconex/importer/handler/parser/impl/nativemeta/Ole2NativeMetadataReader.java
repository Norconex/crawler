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
import java.util.Date;
import java.util.Map;
import java.util.Set;

import org.apache.poi.hpsf.DocumentSummaryInformation;
import org.apache.poi.hpsf.PropertySetFactory;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.tika.io.TikaInputStream;

import com.norconex.commons.lang.file.ContentType;

/**
 * Reads the property sets of a legacy Office file (Word, Excel,
 * PowerPoint, Visio {@code .doc}, {@code .xls}, {@code .ppt}, ...):
 * {@code SummaryInformation} and {@code DocumentSummaryInformation}.
 * Those properties are known by number in the file. They are named here
 * after the specification ({@code Title}, {@code Author},
 * {@code LastAuthor}, {@code CreateTime}, {@code Company}, ...), and
 * custom properties after the name they were given. Dates are written in
 * ISO-8601.
 */
final class Ole2NativeMetadataReader implements NativeMetadataReader {

    private static final Set<String> TYPES = Set.of(
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/vnd.visio",
            "application/x-mspublisher",
            "application/x-tika-msoffice");

    /** [MS-OLEPS] SummaryInformation property identifiers. */
    private static final Map<Long, String> SUMMARY = Map.ofEntries(
            Map.entry(2L, "Title"),
            Map.entry(3L, "Subject"),
            Map.entry(4L, "Author"),
            Map.entry(5L, "Keywords"),
            Map.entry(6L, "Comments"),
            Map.entry(7L, "Template"),
            Map.entry(8L, "LastAuthor"),
            Map.entry(9L, "RevNumber"),
            Map.entry(10L, "EditTime"),
            Map.entry(11L, "LastPrinted"),
            Map.entry(12L, "CreateTime"),
            Map.entry(13L, "LastSaveTime"),
            Map.entry(14L, "PageCount"),
            Map.entry(15L, "WordCount"),
            Map.entry(16L, "CharCount"),
            Map.entry(18L, "ApplicationName"),
            Map.entry(19L, "Security"));

    /** [MS-OLEPS] DocumentSummaryInformation property identifiers. */
    private static final Map<Long, String> DOCUMENT_SUMMARY = Map.ofEntries(
            Map.entry(2L, "Category"),
            Map.entry(3L, "PresentationFormat"),
            Map.entry(4L, "ByteCount"),
            Map.entry(5L, "LineCount"),
            Map.entry(6L, "ParagraphCount"),
            Map.entry(7L, "SlideCount"),
            Map.entry(8L, "NoteCount"),
            Map.entry(9L, "HiddenCount"),
            Map.entry(10L, "MMClipCount"),
            Map.entry(11L, "ScaleCrop"),
            Map.entry(14L, "Manager"),
            Map.entry(15L, "Company"),
            Map.entry(16L, "LinksUpToDate"),
            Map.entry(17L, "CharCountWithSpaces"));

    private static final String SUMMARY_STREAM = "\u0005SummaryInformation";
    private static final String DOCUMENT_SUMMARY_STREAM =
            "\u0005DocumentSummaryInformation";

    @Override
    public boolean supports(ContentType contentType) {
        return TYPES.contains(contentType.toBaseTypeString());
    }

    @Override
    public NativeFields read(TikaInputStream in, String charset)
            throws IOException {
        var found = NativeFields.newMap();
        try (var fs = new POIFSFileSystem(in.getPath().toFile(), true)) {
            var root = fs.getRoot();
            if (root.hasEntry(SUMMARY_STREAM)) {
                try (var dis = fs.createDocumentInputStream(SUMMARY_STREAM)) {
                    var set = PropertySetFactory.create(dis);
                    for (var p : set.getProperties()) {
                        add(found, SUMMARY.get(p.getID()), p.getValue());
                    }
                }
            }
            if (root.hasEntry(DOCUMENT_SUMMARY_STREAM)) {
                try (var dis = fs.createDocumentInputStream(
                        DOCUMENT_SUMMARY_STREAM)) {
                    var set = PropertySetFactory.create(dis);
                    for (var p : set.getProperties()) {
                        add(found, DOCUMENT_SUMMARY.get(p.getID()),
                                p.getValue());
                    }
                    if (set instanceof DocumentSummaryInformation dsi) {
                        var custom = dsi.getCustomProperties();
                        if (custom != null) {
                            custom.forEach((k, v) -> add(found, k, v));
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // not a property-bearing file: the parser has its say
            return NativeFields.none();
        } catch (Exception e) {
            return NativeFields.none();
        }
        return found.isEmpty()
                ? NativeFields.none()
                : new NativeFields(found, OoxmlNativeMetadataReader::supersede);
    }

    private static void add(
            Map<String, java.util.List<String>> found,
            String name, Object value) {
        if (name == null || value == null) {
            return;
        }
        var text = value instanceof Date d
                ? d.toInstant().toString()
                : value.toString().trim();
        if (!text.isEmpty()) {
            NativeFields.add(found, name, text);
        }
    }
}
