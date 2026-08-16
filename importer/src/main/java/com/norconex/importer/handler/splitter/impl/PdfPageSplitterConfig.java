/* Copyright 2018-2024 Norconex Inc.
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

import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.handler.splitter.BaseDocumentSplitterConfig;

import lombok.Data;
import lombok.experimental.Accessors;

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
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class PdfPageSplitterConfig extends BaseDocumentSplitterConfig {

    private String referencePagePrefix =
            PdfPageSplitter.DEFAULT_REFERENCE_PAGE_PREFIX;

    /**
     * The matcher of content types to apply splitting on. No attempt to
     * split documents of any other content types will be made. Default is
     * <code>application/pdf</code>.
     * @param contentTypeMatcher content type matcher
     * @return content type matcher
     */
    private final TextMatcher contentTypeMatcher =
            TextMatcher.basic("application/pdf");

    /**
     * The matcher of content types to apply splitting on. No attempt to
     * split documents of any other content types will be made. Default is
     * <code>application/pdf</code>.
     * @param contentTypeMatcher content type matcher
     * @return this
     */
    public PdfPageSplitterConfig setContentTypeMatcher(TextMatcher matcher) {
        contentTypeMatcher.copyFrom(matcher);
        return this;
    }
}
