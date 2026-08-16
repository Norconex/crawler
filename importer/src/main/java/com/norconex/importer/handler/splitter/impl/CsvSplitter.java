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
package com.norconex.importer.handler.splitter.impl;

import static org.apache.commons.lang3.StringUtils.isBlank;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;

import com.norconex.commons.lang.io.CachedInputStream;
import com.norconex.commons.lang.map.Properties;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.doc.DocMetaConstants;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.handler.DocHandlerException;
import com.norconex.importer.handler.splitter.AbstractDocumentSplitter;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReaderBuilder;

import lombok.Data;

/**
 * <p>Split files with Coma-Separated values (or any other characters, like tab)
 * into one document per line.</p>
 *
 * <p>Can be used both as a pre-parse (text documents) or post-parse handler
 * documents.</p>
 *
 * <p>
 * Given this sample CSV file content...
 * </p>
 * <pre>
 * 'clientId','clientName','clientOrg','orgDesc'
 * '123','Joe Dalton','ACME Inc.','Organization\'s description'
 * '345','Avrel Dalton','Daisy Town','Another one'
 * </pre>
 * <p>
 * ... the above example will split the file into two documents (one for each
 * row after the header row):
 * </p>
 *
 * @see <a href="https://crawler.norconex.com/docs/reference/importer/CsvSplitter">
 *      CsvSplitter configuration reference</a>
 */
@Data
public class CsvSplitter extends AbstractDocumentSplitter<CsvSplitterConfig> {

    private final CsvSplitterConfig configuration = new CsvSplitterConfig();

    @Override
    public void split(DocHandlerContext docCtx) throws IOException {
        try {
            var count = new AtomicInteger();
            // Body
            if (!configuration.getFieldMatcher().isSet()) {
                docCtx.childDocs().addAll(
                        doSplitDocument(
                                docCtx, docCtx.input().asInputStream(), count));
                return;
            }
            // Fields
            var docs = docCtx.childDocs();
            docCtx.metadata()
                    .matchKeys(configuration.getFieldMatcher())
                    .forEach((k, vals) -> vals
                            .forEach(row -> docs.addAll(
                                    doSplitDocument(
                                            docCtx,
                                            new ByteArrayInputStream(
                                                    row.getBytes()),
                                            count))));
        } catch (Exception e) {
            throw new DocHandlerException(
                    "Could not split document: " + docCtx.reference(), e);
        }
    }

    private List<Doc> doSplitDocument(
            DocHandlerContext doc, InputStream input, AtomicInteger count) {

        List<Doc> rows = new ArrayList<>();

        var parser = new CSVParserBuilder()
                .withSeparator(configuration.getSeparatorCharacter())
                .withQuoteChar(configuration.getQuoteCharacter())
                .withEscapeChar(configuration.getEscapeCharacter())
                .build();

        //TODO by default (or as an option), try to detect the format of the
        // file (read first few lines and count number of tabs vs coma,
        // quotes per line, etc.
        try (var csvreader =
                new CSVReaderBuilder(
                        new InputStreamReader(input, StandardCharsets.UTF_8))
                                .withSkipLines(configuration.getLinesToSkip())
                                .withCSVParser(parser)
                                .build()) {

            String[] rowColumns;
            String[] colNames = null;
            while ((rowColumns = csvreader.readNextSilently()) != null) {
                var cnt = count.incrementAndGet();
                var childEmbedRef = "row-" + count;
                if (cnt == 1 && configuration.isUseFirstRowAsFields()) {
                    colNames = rowColumns;
                } else {
                    rows.add(
                            parseRow(
                                    doc, rowColumns, colNames, childEmbedRef));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }

    private Doc parseRow(
            DocHandlerContext docCtx, String[] rowColumns,
            String[] colNames,
            String childEmbedRef) {
        var contentStr = new StringBuilder();
        var childMeta = new Properties();
        childMeta.loadFromMap(docCtx.metadata());

        for (var i = 0; i < rowColumns.length; i++) {
            var colPos = i + 1;
            String colName = null;
            if (colNames == null || i >= colNames.length) {
                colName = "column" + colPos;
            } else {
                colName = colNames[i];
            }
            var colValue = rowColumns[i];

            // If a reference column, set reference value
            var refColumn = configuration.getReferenceColumn();
            if (isColumnMatchingNameOrPosition(
                    colName, colPos,
                    isBlank(refColumn) ? List.of() : List.of(refColumn))) {
                childEmbedRef = colValue;
            }
            // If a content column, add it to content
            if (isColumnMatchingNameOrPosition(
                    colName, colPos, configuration.getContentColumns())) {
                if (contentStr.length() > 0) {
                    contentStr.append(" ");
                }
                contentStr.append(colValue);
            }
            childMeta.set(colName, colValue);
        }
        var childDocRef = docCtx.reference() + "!" + childEmbedRef;
        CachedInputStream content = null;
        if (contentStr.length() > 0) {
            content = docCtx.streamFactory().newInputStream(
                    contentStr.toString());
        } else {
            content = docCtx.streamFactory().newInputStream();
        }
        @SuppressWarnings("resource")
        var childDoc = new Doc(childDocRef)
                .setInputStream(content)
                .setMetadata(childMeta);
        childMeta.set(DocMetaConstants.EMBEDDED_REFERENCE, childEmbedRef);

        return childDoc;
    }

    private boolean isColumnMatchingNameOrPosition(
            String colName, int colPosition, List<String> namesOrPossToMatch) {
        if (CollectionUtils.isEmpty(namesOrPossToMatch)) {
            return false;
        }
        for (String nameOrPosToMatch : namesOrPossToMatch) {
            if (StringUtils.isBlank(nameOrPosToMatch)) {
                continue;
            }
            if (Objects.equals(nameOrPosToMatch, colName)
                    || NumberUtils.toInt(nameOrPosToMatch) == colPosition) {
                return true;
            }
        }
        return false;
    }
}
