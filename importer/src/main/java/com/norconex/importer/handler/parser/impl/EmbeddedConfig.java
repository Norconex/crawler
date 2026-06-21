/* Copyright 2016-2024 Norconex Inc.
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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.response.ImporterResponse;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * {@nx.block #doc
 * <p>
 * For documents containing embedded documents (e.g. zip files), the default
 * behavior of this treat them as a single document, merging all
 * embedded documents content and metadata into the parent document.
 * You can tell this parser to "split" embedded
 * documents to have them treated as if they were individual documents.  When
 * split, each embedded documents will go through the entire import cycle,
 * going through your handlers and even this parser again
 * (just like any regular document would).
 * </p>
 * <p>
 * You can also decide to skip the parsing of embedded documents. Either
 * by the content type of the parent document, or the content types of the
 * embedded documents.
 * </p>
 * <p>
 * When embedded are being parsed, the resulting
 * {@link ImporterResponse} will contain nested documents, which in turn,
 * might contain some (tree-like structure).
 * </p>
 * }
 *
 * {@nx.xml.usage
 * <embedded>
 *     <splitEmbeddedOf>
 *       <!-- "matcher" is repeatable -->
 *       <matcher>
 *         (content type matcher of files to split, having their
 *          embedded documents extracted and treated as individual
 *          documents instead)
 *       </matcher>
 *     </splitEmbeddedOf>
 *     <skipEmmbbededOf>
 *       <matcher>
 *         (content type matcher of files you do not want to have their
 *          embedded files parsed)
 *       </matcher>
 *     </skipEmmbbededOf>
 *     <skipEmmbbeded>
 *       <matcher>
 *         (content type matcher of embedded files you do not want parsed)
 *       </matcher>
 *     </skipEmmbbeded>
 * </embedded>
 * }
 *
 * {@nx.xml.example
 * <embedded>
 *   <splitEmbeddedOf>
 *     <matcher>application/zip</matcher>
 *   </splitEmbeddedOf>
 * </embedded>
 * }
 *
 * <p>
 * The above example will treat all documents contained with a Zip file
 * as individual documents, each to be processed separately.
 * </p>
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class EmbeddedConfig implements Serializable { //{

    //TODO add a "discardRootContainer" property (when split)?

    private static final long serialVersionUID = 1L;

    private final List<TextMatcher> splitContentTypes = new ArrayList<>();
    private final List<TextMatcher> skipEmbeddedOfContentTypes =
            new ArrayList<>();
    private final List<TextMatcher> skipEmbeddedContentTypes =
            new ArrayList<>();

    /**
     * Maximum detph of embeded documents to extract. Zero is the
     * root document (original container).
     * @param maxEmbeddedDepth max depth or -1 (default) for unlimited.
     * @return maximum depth of embedded docs to extract
     */
    private int maxEmbeddedDepth = -1;

    /**
     * Gets the content types of container files to split and treat their
     * embedded files as separate documents. Default does not split
     * any type (all embedded documents are merged).
     * @return list of content type matcher
     */
    public List<TextMatcher> getSplitContentTypes() {
        return Collections.unmodifiableList(splitContentTypes);
    }

    /**
     * Gets the content types of container files to split and treat their
     * embedded files as separate documents. Default does not split
     * any type (all embedded documents are merged).
     * @param splitContentTypes list of content type matcher
     * @return this instance
     */
    public EmbeddedConfig setSplitContentTypes(
            List<TextMatcher> splitContentTypes) {
        CollectionUtil.setAll(this.splitContentTypes, splitContentTypes);
        return this;
    }

    /**
     * Gets the content type matchers of container files you do not want to
     * have their embedded files parsed. Embedded files of matching containers
     * will effectively be skipped/ignored.
     * Default parses embedded files of all container file content types.
     * @return list of content type matcher
     */
    public List<TextMatcher> getSkipEmbeddedOfContentTypes() {
        return Collections.unmodifiableList(skipEmbeddedOfContentTypes);
    }

    /**
     * Gets the content type matchers of container files you do not want to
     * have their embedded files parsed. Embedded files of matching containers
     * will effectively be skipped/ignored.
     * Default parses embedded files of all container file content types.
     * @param skipEmbeddedOfContentTypes list of content type matcher
     * @return this instance
     */
    public EmbeddedConfig setSkipEmbeddedOfContentTypes(
            List<TextMatcher> skipEmbeddedOfContentTypes) {
        CollectionUtil.setAll(
                this.skipEmbeddedOfContentTypes, skipEmbeddedOfContentTypes);
        return this;
    }

    /**
     * Gets the content types of container files to split and treat their
     * embedded files as separate documents. Default does not split
     * any type (all embedded documents are merged).
     * @return list of content type matcher
     */
    public List<TextMatcher> getSkipEmbeddedContentTypes() {
        return Collections.unmodifiableList(skipEmbeddedContentTypes);
    }

    /**
     * Gets the content types of container files to split and treat their
     * embedded files as separate documents. Default does not split
     * any type (all embedded documents are merged).
     * @param skipEmbeddedContentTypes list of content type matcher
     * @return this instance
     */
    public EmbeddedConfig setSkipEmbeddedContentTypes(
            List<TextMatcher> skipEmbeddedContentTypes) {
        CollectionUtil.setAll(
                this.skipEmbeddedContentTypes, skipEmbeddedContentTypes);
        return this;
    }
}
