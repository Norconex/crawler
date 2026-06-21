/* Copyright 2014-2024 Norconex Inc.
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

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.commons.lang.io.TextReader;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.util.chunk.ChunkedTextSupport;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>Detects a document language based on Apache Tika language detection
 * capability.
 * It adds the detected language to the
 * "<code>document.language</code>" metadata field.
 * Optionally adds all potential languages detected with their
 * probability score as well as additional fields following this pattern:</p>
 * <pre>
 * document.language.&lt;rank&gt;.tag
 * document.language.&lt;rank&gt;.probability</pre>
 * <p>
 * <code>&lt;rank&gt;</code> is to indicate the match order, based
 * on match probability score (starting at 1).
 * </p>
 *
 * <p>This tagger can be used both as a pre-parse (on text only)
 * or post-parse handler.</p>
 *
 * <h2>Accuracy:</h2>
 * <p>
 * To obtain optimal detection, long enough text is expected.  The default
 * detection algorithm is optimized for document with lots of text.
 * This tagger relies on Tika language detection capabilities and future
 * versions may provide better precision for documents made of short
 * text (e.g. tweets, comments, etc).
 * </p>
 * <p>
 * If you know what mix of languages are used by your site(s), you can increase
 * accuracy in many cases by limiting the set of languages supported
 * for detection.
 * </p>
 *
 * <h2>Supported Languages:</h2>
 * <p>
 * Languages are represented as code values. As of 2.6.0, at least the
 * following 70 languages are supported by the Tika version used:
 * </p>
 *
 * <ul>
 *   <li>af Afrikaans</li>
 *   <li>an Aragonese</li>
 *   <li>ar Arabic</li>
 *   <li>ast Asturian</li>
 *   <li>be Belarusian</li>
 *   <li>br Breton</li>
 *   <li>ca Catalan</li>
 *   <li>bg Bulgarian</li>
 *   <li>bn Bengali</li>
 *   <li>cs Czech</li>
 *   <li>cy Welsh</li>
 *   <li>da Danish</li>
 *   <li>de German</li>
 *   <li>el Greek</li>
 *   <li>en English</li>
 *   <li>es Spanish</li>
 *   <li>et Estonian</li>
 *   <li>eu Basque</li>
 *   <li>fa Persian</li>
 *   <li>fi Finnish</li>
 *   <li>fr French</li>
 *   <li>ga Irish</li>
 *   <li>gl Galician</li>
 *   <li>gu Gujarati</li>
 *   <li>he Hebrew</li>
 *   <li>hi Hindi</li>
 *   <li>hr Croatian</li>
 *   <li>ht Haitian</li>
 *   <li>hu Hungarian</li>
 *   <li>id Indonesian</li>
 *   <li>is Icelandic</li>
 *   <li>it Italian</li>
 *   <li>ja Japanese</li>
 *   <li>km Khmer</li>
 *   <li>kn Kannada</li>
 *   <li>ko Korean</li>
 *   <li>lt Lithuanian</li>
 *   <li>lv Latvian</li>
 *   <li>mk Macedonian</li>
 *   <li>ml Malayalam</li>
 *   <li>mr Marathi</li>
 *   <li>ms Malay</li>
 *   <li>mt Maltese</li>
 *   <li>ne Nepali</li>
 *   <li>nl Dutch</li>
 *   <li>no Norwegian</li>
 *   <li>oc Occitan</li>
 *   <li>pa Punjabi</li>
 *   <li>pl Polish</li>
 *   <li>pt Portuguese</li>
 *   <li>ro Romanian</li>
 *   <li>ru Russian</li>
 *   <li>sk Slovak</li>
 *   <li>sl Slovene</li>
 *   <li>so Somali</li>
 *   <li>sq Albanian</li>
 *   <li>sr Serbian</li>
 *   <li>sv Swedish</li>
 *   <li>sw Swahili</li>
 *   <li>ta Tamil</li>
 *   <li>te Telugu</li>
 *   <li>th Thai</li>
 *   <li>tl Tagalog</li>
 *   <li>tr Turkish</li>
 *   <li>uk Ukrainian</li>
 *   <li>ur Urdu</li>
 *   <li>vi Vietnamese</li>
 *   <li>yi Yiddish</li>
 *   <li>zh-cn Simplified Chinese</li>
 *   <li>zh-tw Traditional Chinese</li>
 * </ul>
 *
 * <p>
 * It is possible more will be supported automatically with future Tika
 * upgrades.
 * </p>
 *
 * <p>If you do not restrict the list of language candidates to detect,
 * the default behavior is to try match all languages currently supported.
 * </p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.LanguageTagger"
 *     keepProbabilities="(false|true)"
 *     toField="(custom target field to store the language)"
 *     fallbackLanguage="(default language when detection failed)"
 *     {@nx.include com.norconex.importer.handler.tagger.AbstractStringTagger#attributes}>
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <languages>
 *     (CSV list of language tag candidates. Defaults to the above list.)
 *   </languages>
 *
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="LanguageTagger" fallbackLanguage="en" >
 *   <languages>en, fr</languages>
 * </handler>
 * }
 * <p>
 * The above example detects whether pages are English or French, falling back
 * to English if detection failed.
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class LanguageTransformerConfig implements ChunkedTextSupport {

    private int maxReadSize = TextReader.DEFAULT_MAX_READ_SIZE;
    private Charset sourceCharset = null;

    /**
     * Whether to keep the match probabilities for each languages
     * detected.  Default is <code>false</code>.
     * @param keepProbabilities <code>true</code> to keep probabilities
     * @return <code>true</code> if probability kept
     */
    private boolean keepProbabilities;

    private final List<String> languages = new ArrayList<>();

    private final TextMatcher fieldMatcher = new TextMatcher();

    /**
     * The fallback language when none are detected.  Default behavior
     * is to not tag incoming documents with a language field when no detection
     * occurs.
     * @param fallbackLanguage the default languages when no detection
     * @return the fallback language
     */
    private String fallbackLanguage;

    /**
     * The language candidates for language detection.
     * @return languages to consider for detection
     */
    public List<String> getLanguages() {
        return Collections.unmodifiableList(languages);
    }

    /**
     * The language candidates for language detection.
     * @param languages languages to consider for detection
     */
    public LanguageTransformerConfig setLanguages(List<String> languages) {
        CollectionUtil.setAll(this.languages, languages);
        return this;
    }

    /**
     * Gets a matcher for fields to use to detect language. When not
     * specified (default), use the document content instead.
     * @return field matcher
     */
    @Override
    public TextMatcher getFieldMatcher() {
        return fieldMatcher;
    }

    /**
     * Sets a matcher for fields to use to detect language. When not
     * specified (default), use the document content instead.
     * @param fieldMatcher field matcher
     */
    public LanguageTransformerConfig setFieldMatcher(
            TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }
}
