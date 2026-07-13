/* Copyright 2014-2025 Norconex Inc.
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;

import org.apache.tika.langdetect.optimaize.OptimaizeLangDetector;
import org.apache.tika.language.detect.LanguageDetector;
import org.apache.tika.language.detect.LanguageResult;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.importer.doc.DocMetaConstants;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;
import com.norconex.importer.util.chunk.ChunkedTextReader;

import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class LanguageTransformer
        implements ConfigurableDocHandler<LanguageTransformerConfig> {

    private final LanguageTransformerConfig configuration =
            new LanguageTransformerConfig();

    //TODO Check if doc.size is defined in metadata? If so, use it to
    //determine if we are going with small or long text?

    //TODO provide ways to overwrite or specify custom language profiles
    // in this tagger configuration?

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Getter(value = AccessLevel.NONE)
    @Setter(value = AccessLevel.NONE)
    @JsonIgnore
    private LanguageDetector detector;
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Getter(value = AccessLevel.NONE)
    @Setter(value = AccessLevel.NONE)
    @JsonIgnore
    private final Comparator<LanguageResult> langResultComparator =
            Comparator.comparing(LanguageResult::getRawScore).reversed();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {
        ensureDetectorInitialization();

        ChunkedTextReader.from(configuration).read(docCtx, chunk -> {
            var results = detector.detectAll(chunk.getText());
            if (results.isEmpty()
                    || (results.size() == 1 &&
                            results.contains(LanguageResult.NULL))) {
                LOG.debug("No language found, using fallback language for {}.",
                        docCtx.reference());
                docCtx.metadata().set(DocMetaConstants.LANGUAGE,
                        configuration.getFallbackLanguage());
            } else {
                Collections.sort(results, langResultComparator);
                docCtx.metadata().set(
                        DocMetaConstants.LANGUAGE,
                        results.get(0).getLanguage());

                if (configuration.isKeepProbabilities()) {
                    var count = 0;
                    for (LanguageResult lang : results) {
                        count++;
                        var prefix = DocMetaConstants.LANGUAGE + "." + count;
                        docCtx.metadata().set(
                                prefix + ".tag", lang.getLanguage());
                        docCtx.metadata().set(
                                prefix + ".probability",
                                lang.getRawScore());
                    }
                }
            }

            // we only do it on first chunk, so leave now.
            return false;
        });
        return true;
    }

    private synchronized void ensureDetectorInitialization()
            throws IOException {
        if (detector == null) {
            var d = new OptimaizeLangDetector();
            if (configuration.getLanguages().isEmpty()) {
                d.loadModels();
            } else {
                d.loadModels(new HashSet<>(configuration.getLanguages()));
            }
            detector = d;
        }
    }
}
