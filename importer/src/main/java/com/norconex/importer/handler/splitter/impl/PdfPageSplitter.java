/* Copyright 2018-2026 Norconex Inc.
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

import static org.apache.commons.lang3.StringUtils.trimToEmpty;

import java.io.IOException;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;

import com.norconex.commons.lang.map.Properties;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.doc.DocMetaConstants;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.handler.DocHandlerException;
import com.norconex.importer.handler.splitter.AbstractDocumentSplitter;
import com.norconex.importer.util.MatchUtil;

import lombok.Data;

/**
 * <p>
 * Split PDFs pages so each pages are treated as individual documents. May not
 * work on all PDFs (e.g., encrypted PDFs).
 * </p>
 *
 * <p>
 * The original PDF is kept intact. If you want to eliminate it to keep only
 * the split pages, make sure to filter it.  You can do so by filtering
 * out PDFs without one of these two fields added to each pages:
 * <code>document.pdf.pageNumber</code> or
 * <code>document.pdf.numberOfPages</code>.  A filtering example:
 * </p>
 *
 * <p>
 * By default this splitter restricts its use to
 * <code>document.contentType</code> matching <code>application/pdf</code>.
 * </p>
 *
 * <p>Should be used as a pre-parse handler.</p>
 *
 * @see <a href="https://crawler.norconex.com/docs/reference/importer/PdfPageSplitter">
 *      PdfPageSplitter configuration reference</a>
 */
@Data
public class PdfPageSplitter
        extends AbstractDocumentSplitter<PdfPageSplitterConfig> {

    public static final String DOC_PDF_PAGE_NO = "document.pdf.pageNumber";
    public static final String DOC_PDF_TOTAL_PAGES =
            "document.pdf.numberOfPages";

    public static final String DEFAULT_REFERENCE_PAGE_PREFIX = "#";

    private final PdfPageSplitterConfig configuration =
            new PdfPageSplitterConfig();

    @Override
    public void split(DocHandlerContext docCtx) throws DocHandlerException {

        // Make sure we are not splitting a page that was already split
        if (!MatchUtil.matchesContentType(
                configuration.getContentTypeMatcher(),
                docCtx.contentType())
                || (docCtx.metadata().getInteger(
                        DOC_PDF_PAGE_NO, 0) > 0)) {
            return;
        }

        try (var document = Loader.loadPDF(
                docCtx.input().asInputStream()
                        .readAllBytes())) {

            // Make sure we are not splitting single pages.
            if (document.getNumberOfPages() <= 1) {
                docCtx.metadata().set(DOC_PDF_PAGE_NO, 1);
                docCtx.metadata().set(DOC_PDF_TOTAL_PAGES, 1);
                return;
            }
            var pageDocs = docCtx.childDocs();

            var splitter = new Splitter();
            var splittedDocuments = splitter.split(document);
            var pageNo = 0;
            for (PDDocument page : splittedDocuments) {
                pageNo++;

                var pageRef = docCtx.reference()
                        + trimToEmpty(configuration
                                .getReferencePagePrefix())
                        + pageNo;

                // metadata
                var pageMeta = new Properties();
                pageMeta.loadFromMap(docCtx.metadata());

                var pageDoc = new Doc(pageRef);

                pageMeta.set(
                        DocMetaConstants.EMBEDDED_REFERENCE,
                        Integer.toString(pageNo));

                pageDoc.addParentReference(docCtx.reference());

                pageMeta.set(DOC_PDF_PAGE_NO, pageNo);
                pageMeta.set(DOC_PDF_TOTAL_PAGES,
                        document.getNumberOfPages());

                // a single page should not be too big to store in memory
                var os = new ByteArrayOutputStream();
                try (page) {
                    page.save(os);
                }
                pageDoc.setInputStream(
                        docCtx.streamFactory()
                                .newInputStream(os
                                        .toInputStream()))
                        .setMetadata(pageMeta);
                pageDocs.add(pageDoc);
            }
        } catch (IOException e) {
            throw new DocHandlerException(
                    "Could not split PDF: "
                            + docCtx.reference(),
                    e);
        }
    }
}
