/* Copyright 2015-2025 Norconex Inc.
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
package com.norconex.importer.handler.transformer.impl;

import com.norconex.importer.handler.BaseDocHandlerConfig;

import java.nio.charset.Charset;

import com.norconex.commons.lang.io.TextReader;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.doc.DocMetaConstants;
import com.norconex.importer.util.chunk.ChunkedTextSupport;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>Attempts to generate a title from the document content (default) or
 * a specified metadata field. It does not consider the document
 * format/structure nor does it weight some terms more than others.
 * For instance, it would not
 * consider text found in &lt;H1&gt; tags more importantly than other
 * text in HTML documents.</p>
 *
 * <p>If {@link #isDetectHeading()} returns <code>true</code>, this handler
 * will check if the content starts with a stand-alone, single-sentence line
 * (which is assumed to be the actual title).
 * That is, a line of text with only one sentence in it, followed by one or
 * more new line characters. To help
 * eliminate cases where such sentence are inappropriate, you can specify a
 * minimum and maximum number of characters that first line should have
 * with {@link #setDetectHeadingMinLength(int)} and
 * {@link #setDetectHeadingMaxLength(int)} (e.g. to ignore "Page 1" text and
 * the like).</p>
 *
 * <p>Unless a target field name is provided, the default field name
 * where the title will be stored is <code>document.generatedTitle</code>.
 *
 * <h2>Storing values in an existing field</h2>
 * <p>
 * If a target field with the same name already exists for a document,
 * values will be added to the end of the existing value list.
 * It is possible to change this default behavior by supplying a
 * {@link PropertySetter}.
 * </p>
 *
 * <p>If it cannot generate a title, it will fall-back to retrieving the
 * first sentence from the text.</p>
 *
 * <p>The generated title length is limited to 150 characters by default.
 * You can change that limit by using
 * {@link #setTitleMaxLength(int)}. Text larger than the max limit will be
 * truncated and three dots will be added in square brackets ([...]).
 * To remove the limit,
 * use -1 (or constant {@link #UNLIMITED_TITLE_LENGTH}).</p>
 *
 * <p>This class should be used as a post-parsing handler only
 * (or otherwise on unformatted text).</p>
 *
 * <p>The algorithm to detect titles is quite basic.
 * It uses a generic statistics-based approach to weight each sentences
 * up to a certain amount, and simply returns the sentence with the highest
 * attributed weight given a minimum threshold has been met.  You are strongly
 * encouraged to use a more sophisticated summarization engine if you want more
 * accurate titles generated.
 * </p>
 *
 * <h2>Max read size</h2>
 * <p>This tagger will only analyze up to the first
 * 10,000 characters. You can change this maximum
 * with {@link #setMaxReadSize(int)}. Given this class is not
 * optimized for large content analysis, setting a huge maximum number
 * of characters could cause serious performance issues on large
 * large files.</p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.TitleGeneratorTagger"
 *     {@nx.include com.norconex.importer.handler.tagger.AbstractStringTagger#attributes}
 *     fromField="(field of text to use/default uses document content)"
 *     toField="(target field where to store generated title)"
 *     {@nx.include com.norconex.commons.lang.map.PropertySetter#attributes}
 *     titleMaxLength="(max num of chars for generated title)"
 *     detectHeading="[false|true]"
 *     detectHeadingMinLength="(min length a heading title can have)"
 *     detectHeadingMaxLength="(max length a heading title can have)">
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="TitleGeneratorTagger"
 *     toField="title" titleMaxLength="200" detectHeading="true" />
 * }
 * <p>
 * The above will check if the first line looks like a title and if not,
 * it will store the first sentence, up to 200 characters, in a field called
 * title.
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class TitleGeneratorTransformerConfig extends BaseDocHandlerConfig
        implements ChunkedTextSupport {

    public static final String DEFAULT_TO_FIELD =
            DocMetaConstants.GENERATED_TITLE;
    public static final int DEFAULT_TITLE_MAX_LENGTH = 150;
    public static final int UNLIMITED_TITLE_LENGTH = -1;
    public static final int DEFAULT_HEADING_MIN_LENGTH = 10;
    public static final int DEFAULT_HEADING_MAX_LENGTH = 150;
    public static final int DEFAULT_MAX_READ_SIZE = 10000;

    private int maxReadSize = TextReader.DEFAULT_MAX_READ_SIZE;
    private Charset sourceCharset;
    private final TextMatcher fieldMatcher = new TextMatcher();

    //TODO have a max num terms?

    private String fromField;
    private String toField = DEFAULT_TO_FIELD;
    private int titleMaxLength = DEFAULT_TITLE_MAX_LENGTH;
    private boolean detectHeading;
    private int detectHeadingMinLength = DEFAULT_HEADING_MIN_LENGTH;
    private int detectHeadingMaxLength = DEFAULT_HEADING_MAX_LENGTH;
    /**
     * The property setter to use when a value is set.
     * @param onSet property setter
     * @return property setter
     */
    private PropertySetter onSet;

    /**
     * Gets source field matcher for fields to use to generate title.
     * @return field matcher
     */
    @Override
    public TextMatcher getFieldMatcher() {
        return fieldMatcher;
    }

    /**
     * Sets source field matcher for fields to use to generate title.
     * @param fieldMatcher field matcher
     */
    public TitleGeneratorTransformerConfig setFieldMatcher(
            TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }
}
