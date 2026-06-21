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
package com.norconex.importer.handler.splitter.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.importer.handler.splitter.BaseDocumentSplitterConfig;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>Translate documents using one of the supported translation API.  The
 * following lists the supported APIs, along with the required authentication
 * properties or settings for each:</p>
 * <ul>
 *   <li><a href="http://blogs.msdn.com/b/translation/p/gettingstarted1.aspx">microsoft</a>
 *     <ul>
 *       <li>clientId</li>
 *       <li>clientSecret</li>
 *     </ul>
 *   </li>
 *   <li><a href="https://cloud.google.com/translate/">google</a>
 *     <ul>
 *       <li>apiKey</li>
 *     </ul>
 *   </li>
 *   <li><a href="http://www.lingo24.com/">lingo24</a>
 *     <ul>
 *       <li>userKey</li>
 *     </ul>
 *   </li>
 *   <li><a href="http://www.statmt.org/moses/">moses</a>
 *     <ul>
 *       <li>smtPath</li>
 *       <li>scriptPath</li>
 *     </ul>
 *   </li>
 *   <li><a href="https://tech.yandex.com/translate/">yandex</a>
 *     <ul>
 *       <li>apiKey</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>For example, the
 * Microsoft Translation API requires a client ID and a client
 * secret,
 * both obtained on Microsoft Azure Marketplace with your Microsoft account.
 * </p><p>
 * Translated documents will have the original document language stored in
 * a field "document.translatedFrom".
 * </p><p>
 * This class is not a document "splitter" per se, but like regular splitters,
 * the translation
 * will create children documents for each translation performed.  The parent
 * document will always remain the original document, while the children
 * will always be the translations.</p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.splitter.impl.TranslatorSplitter"
 *     api="(microsoft|google|lingo24|moses|yandex)">
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <ignoreContent>(false|true)</ignoreContent>
 *   <ignoreNonTranslatedFields>(false|true)</ignoreNonTranslatedFields>
 *   <fieldsToTranslate>(coma-separated list of fields)</fieldsToTranslate>
 *   <sourceLanguageField>(field containing language)</sourceLanguageField>
 *   <sourceLanguage>(language when no source language field)</sourceLanguage>
 *   <targetLanguages>(coma-separated list of languages)</targetLanguages>
 *
 *   <!-- Microsoft -->
 *   <clientId>...</clientId>
 *   <clientSecret>...</clientSecret>
 *
 *   <!-- Google -->
 *   <apiKey>...</apiKey>
 *
 *   <!-- Lingo24 -->
 *   <userKey>...</userKey>
 *
 *   <!-- Moses -->
 *   <smtPath>...</smtPath>
 *   <scriptPath>...</scriptPath>
 *
 *   <!-- Yandex -->
 *   <apiKey>...</apiKey>
 *
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="TranslatorSplitter" api="google">
 *     <sourceLanguageField>langField</sourceLanguageField>
 *     <targetLanguages>fr</targetLanguages>
 *     <apiKey>...MYKEYHERE...</apiKey>
 * </handler>
 * }
 *
 * <p>
 * The above example uses the Google translation API to translate documents into
 * French, taking the source document language from a field called "langField".
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class TranslatorSplitterConfig extends BaseDocumentSplitterConfig {

    public static final String API_MICROSOFT = "microsoft";
    public static final String API_GOOGLE = "google";
    public static final String API_LINGO24 = "lingo24";
    public static final String API_MOSES = "moses";
    public static final String API_YANDEX = "yandex";

    /**
     * Supported translation service API name.
     * One of: "microsoft", "google", "lingo24", "moses", "yandex".
     * @param api translation api name
     * @return translation api name
     */
    private String api;

    /**
     * Optional matcher of fields holding content to translate instead
     * of document body.
     * @param fieldMatcher matcher of fields to translate
     * @return matcher of fields to translate
     */
    private final TextMatcher fieldMatcher = new TextMatcher();

    /**
     * Whether to ignore fields not translated when creating child documents.
     * Set to <code>true</code> if you just want children documents to contain
     * fields for which there was a translation (and possibly, the translated
     * content).
     * @param ignoreNonTranslatedFields <code>true</code> to ignore non
     *      translated fields
     * @return to ignore non translated fields
     */
    private boolean ignoreNonTranslatedFields;

    //MAYBE method: add a field with _fr suffix
    // splitter is for new docs... adding field should be in transformer?

    /**
     * The name of a field containing a value representing the original document
     * language.
     * @param sourceLanguageField field holding the document language
     * @return field holding the document language
     */
    private String sourceLanguageField;

    /**
     * The original document language.
     * @param sourceLanguage document language
     * @return document language
     */
    private String sourceLanguage;

    /**
     * Which languages to translate to.
     * @param targetLanguages languages to translate to
     * @return languages to translate to
     */
    private final List<String> targetLanguages = new ArrayList<>();

    // Microsoft
    /**
     * Microsoft API client id.
     * @param clientId client id
     * @return client id
     */
    private String clientId;

    /**
     * Microsoft API client secret.
     * @param clientSecret client secret
     * @return client secret
     */
    private String clientSecret;

    // Google and Yandex
    /**
     * Google or Yandex API key.
     * @param apiKey API key
     * @return API key
     */
    private String apiKey;

    // Lingo24
    /**
     * Lingo24 user key.
     * @param userKey user key
     * @return user key
     */
    private String userKey;

    // Moses
    /**
     * Moses "smtPath".
     * @param smtPath Moses "smtPath"
     * @return Moses "smtPath"
     */
    private String smtPath;

    /**
     * Moses script path.
     * @param scriptPath script path
     * @return script path
     */
    private String scriptPath;

    public TranslatorSplitterConfig setFieldMatcher(TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
        return this;
    }

    public List<String> getTargetLanguages() {
        return Collections.unmodifiableList(targetLanguages);
    }

    public TranslatorSplitterConfig setTargetLanguages(
            List<String> targetLanguages) {
        CollectionUtil.setAll(this.targetLanguages, targetLanguages);
        return this;
    }
}
