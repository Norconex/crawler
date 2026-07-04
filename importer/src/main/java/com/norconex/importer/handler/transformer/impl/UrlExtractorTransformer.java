/* Copyright 2020-2024 Norconex Inc.
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
import java.io.Reader;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.commons.lang.io.TextReader;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.commons.lang.text.Regex;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;

import lombok.Data;

/**
 * <p>
 * Extracts unique URLs matching specific patterns in plain text content and
 * store them in a given field.
 * </p>
 * <p>
 * URL-matching patterns used are relatively simple. It looks for strings
 * starting with <code>http://</code>, <code>https://</code>,
 * or <code>www.</code>.  The later is prefixed with <code>https://</code>
 * when encountered (to make it absolute).
 * </p>
 * <p>
 * The matching is case-insensitive. If you need alternate ways to detect URLs,
 * you can use a combination of {@link RegexTagger}, {@link ReplaceTagger}, or
 * create your own implementation.
 * </p>
 *
 * <h2>Storing values in an existing field</h2>
 * <p>
 * If a target field with the same name already exists for a document,
 * values will be added to the end of the existing value list.
 * It is possible to change this default behavior by supplying a
 * {@link PropertySetter}.
 * </p>
 * <p>
 * If no URLs are found, the target field values (if any) are left intact.
 * </p>
 *
 * <h2>Content source</h2>
 * <p>
 * It is possible to specify a <code>fromField</code>
 * as the source of the text to use instead of using the document content.
 * </p>
 *
 * <p>This class is typically e used as a post-parsing handler only
 * (to ensure we are dealing with text).</p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.URLExtractorTagger"
 *     toField="(target field where to store extracted URLs)"
 *     maxReadSize="(max characters to read at once)"
 *     {@nx.include com.norconex.importer.handler.tagger.AbstractCharStreamTagger#attributes}
 *     {@nx.include com.norconex.commons.lang.map.PropertySetter#attributes}>
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <fieldMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *     (Optional field of text to use. Default uses document content.)
 *   </fieldMatcher>
 *
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="URLExtractorTagger" toField="documentURLs">
 *   <restrictTo>
 *     <fieldMatcher>document.contentType</fieldMatcher>
 *     <valueMatcher>application/pdf</valueMatcher>
 *   </restrictTo>
 * </handler>
 * }
 * <p>
 * The above example is used as a post-parse handler. It detects URLs
 * in parsed PDFs and store those URLs in a field call "documentURLs".
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
public class UrlExtractorTransformer
        implements ConfigurableDocHandler<UrlExtractorTransformerConfig> {

    private static final Pattern URL_PATTERN =
            Regex.compileDotAll("\\b(https?:|www\\.)[^\\s]+", true);

    private final UrlExtractorTransformerConfig configuration =
            new UrlExtractorTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {

        if (StringUtils.isBlank(configuration.getToField())) {
            throw new IllegalArgumentException("\"toField\" cannot be blank.");
        }

        Set<String> urls = new HashSet<>();

        if (configuration.getFieldMatcher().isSet()) {
            extractMetadataURLs(urls, docCtx.metadata());
        } else {
            extractContentURLs(
                    urls, docCtx.input().asReader(
                            configuration.getSourceCharset()));
        }

        if (!urls.isEmpty()) {
            PropertySetter.orAppend(configuration.getOnSet()).apply(
                    docCtx.metadata(), configuration.getToField(), urls);
        }
        return true;
    }

    private void extractMetadataURLs(Set<String> urls, Properties meta) {
        for (String text : meta.matchKeys(configuration.getFieldMatcher())
                .valueList()) {
            extractURLs(urls, text);
        }
    }

    private void extractContentURLs(Set<String> urls, Reader reader)
            throws IOException {
        String text = null;
        try (var tr = new TextReader(reader, configuration.getMaxReadSize())) {
            while ((text = tr.readText()) != null) {
                extractURLs(urls, text);
            }
        }
    }

    private void extractURLs(Set<String> urls, String text) {
        var m = URL_PATTERN.matcher(text);
        while (m.find()) {
            var url = m.group();
            if (!url.startsWith("http")) {
                url = "https://" + url;
            }
            url = StringUtils.stripEnd(url, ".,");
            urls.add(url);
        }
    }
}
