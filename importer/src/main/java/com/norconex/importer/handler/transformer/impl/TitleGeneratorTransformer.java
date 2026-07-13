/* Copyright 2015-2024 Norconex Inc.
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

import java.io.IOException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.util.chunk.ChunkedTextReader;

import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

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
public class TitleGeneratorTransformer
        implements ConfigurableDocHandler<TitleGeneratorTransformerConfig> {

    // Min. length a term should have to be considered valuable
    private static final int MIN_TERM_LENGTH = 4;
    // Min. number of occurrences a term should have to be considered valuable
    private static final int MIN_OCCURENCES = 3;

    private final TitleGeneratorTransformerConfig configuration =
            new TitleGeneratorTransformerConfig();

    @JsonIgnore
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Getter(value = AccessLevel.NONE)
    private final EntryValueComparator entryValueComparator =
            new EntryValueComparator();

    private static final Pattern PATTERN_HEADING = Pattern.compile(
            "^([^\\n\\r]+)[\\n\\r]", Pattern.DOTALL);

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {

        ChunkedTextReader.from(configuration).read(docCtx, chunk -> {
            // The first chunk already did the title generation.
            // If title already exists and not overwriting, leave now
            if (chunk.getChunkIndex() > 0
                    || (PropertySetter.OPTIONAL == configuration.getOnSet()
                            && StringUtils.isNotBlank(
                                    docCtx.metadata().getString(
                                            getTargetField())))) {
                return false;
            }

            // Get the text to evaluate
            var text = StringUtils.trimToEmpty(chunk.getText());
            String title = null;

            // Try detecting if there is a text heading
            if (configuration.isDetectHeading()) {
                title = getHeadingTitle(text);
            }

            // No heading, then try stats-based summarizing
            if (StringUtils.isBlank(title)) {
                title = summarize(text);
            }

            // If we got one, store it
            if (StringUtils.isNotBlank(title)) {
                if (configuration.getTitleMaxLength() >= 0
                        && title.length() > configuration.getTitleMaxLength()) {
                    title = StringUtils.substring(
                            title, 0, configuration.getTitleMaxLength());
                    title += "[...]";
                }
                PropertySetter.orAppend(configuration.getOnSet()).apply(
                        docCtx.metadata(), getTargetField(), title);
            }
            return true;
        });
        return true;
    }

    private String getTargetField() {
        return StringUtils.firstNonBlank(
                configuration.getToField(),
                TitleGeneratorTransformerConfig.DEFAULT_TO_FIELD);
    }

    private String getHeadingTitle(String text) {
        String firstLine = null;
        var m = PATTERN_HEADING.matcher(text);
        if (m.find()) {
            firstLine = StringUtils.trim(m.group());
        }

        // if more than one sentence, ignore
        // must match min/max lengths.
        if (StringUtils.isBlank(firstLine)
                || (StringUtils.split(firstLine, "?!.").length != 1)
                || firstLine.length() < configuration
                        .getDetectHeadingMinLength()
                || firstLine.length() > configuration
                        .getDetectHeadingMaxLength()) {
            return null;
        }
        return firstLine;
    }

    private String summarize(String text) {

        var index = indexText(text);
        if (index.sentences.isEmpty()) {
            return StringUtils.EMPTY;
        }
        var topScore = 0L;
        var topSentence = index.sentences.get(0);
        for (String sentence : index.sentences) {
            var score = 0L;
            var densityFactor = 500L - sentence.length();
            for (TermOccurence to : index.terms) {
                var m = Pattern.compile(
                        "\\b\\Q" + to.term + "\\E\\b").matcher(sentence);
                var count = 0;
                while (m.find()) {
                    count++;
                }
                if (count > 0) {
                    score += (count * to.occurence * densityFactor);
                }
            }
            if (score > topScore) {
                topScore = score;
                topSentence = sentence;
            }
        }
        return topSentence;
    }

    private Index indexText(String text) {
        var index = new Index();
        ConcurrentMap<String, AtomicInteger> terms = new ConcurrentHashMap<>();

        // Allow to pass locale, based on language field?
        var breakIterator = BreakIterator.getSentenceInstance();
        breakIterator.setText(text);

        var start = breakIterator.first();
        var end = breakIterator.next();
        while (end != BreakIterator.DONE) {
            var matchText = text.substring(start, end).trim();
            var sentences = matchText.split("[\\n\\r]");
            for (String sentence : sentences) {
                var s = StringUtils.trimToNull(sentence);
                if (s != null
                        && Character.isLetterOrDigit(sentence.codePointAt(0))) {
                    index.sentences.add(sentence);
                    breakWords(sentence, terms);
                }
            }
            start = end;
            end = breakIterator.next();
        }

        List<Entry<String, AtomicInteger>> sorted =
                new ArrayList<>(terms.entrySet());
        Collections.sort(sorted, entryValueComparator);
        for (Entry<String, AtomicInteger> entry : sorted) {
            var term = entry.getKey();
            var occurences = entry.getValue().get();
            if (term.length() >= MIN_TERM_LENGTH
                    && occurences >= MIN_OCCURENCES) {
                index.terms.add(new TermOccurence(term, occurences));
            }
        }
        return index;
    }

    private void breakWords(
            String sentence, ConcurrentMap<String, AtomicInteger> terms) {

        var wordIterator = BreakIterator.getWordInstance();
        wordIterator.setText(sentence);
        var start = wordIterator.first();
        var end = wordIterator.next();

        while (end != BreakIterator.DONE) {
            var word = sentence.substring(start, end);
            if (Character.isLetterOrDigit(word.codePointAt(0))) {
                terms.putIfAbsent(word, new AtomicInteger(0));
                terms.get(word).incrementAndGet();
            }
            start = end;
            end = wordIterator.next();
        }
    }

    //--- Inner classes --------------------------------------------------------
    @EqualsAndHashCode
    @ToString
    class EntryValueComparator
            implements Comparator<Entry<String, AtomicInteger>> {
        @Override
        public int compare(
                Entry<String, AtomicInteger> o1,
                Entry<String, AtomicInteger> o2) {
            return Integer.compare(o2.getValue().get(), o1.getValue().get());
        }
    }

    @EqualsAndHashCode
    @ToString
    class Index {
        private final List<String> sentences = new ArrayList<>();
        private final List<TermOccurence> terms = new ArrayList<>();
    }

    @EqualsAndHashCode
    @ToString
    class TermOccurence implements Comparable<TermOccurence> {
        private final String term;
        private final int occurence;

        public TermOccurence(String term, int occurence) {
            this.term = term;
            this.occurence = occurence;
        }

        @Override
        public int compareTo(TermOccurence o) {
            return Integer.compare(occurence, o.occurence);
        }
    }
}
