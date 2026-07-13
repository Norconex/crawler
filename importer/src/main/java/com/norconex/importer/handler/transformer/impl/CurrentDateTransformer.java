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
import java.text.SimpleDateFormat;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;

import lombok.Data;

/**
 * <p>Adds the current computer UTC date to the specified <code>field</code>.
 * If no <code>field</code> is provided, the date will be added to
 * <code>document.importedDate</code>.
 * </p>
 * <p>
 * The default date format is EPOCH
 * (the difference, measured in milliseconds, between the current time and
 * midnight, January 1, 1970 UTC).
 * A custom date format can be specified with the <code>format</code>
 * attribute, as per the
 * formatting options found on {@link SimpleDateFormat}.
 * </p>
 *
 * <h2>Storing values in an existing field</h2>
 * <p>
 * If a target field with the same name already exists for a document,
 * values will be added to the end of the existing value list.
 * It is possible to change this default behavior by supplying a
 * {@link PropertySetter}.
 * </p>
 *
 * <p>Can be used both as a pre-parse or post-parse handler.</p>
 *
 * <p>It is possible to specify a locale used for formatting
 * dates. The locale is the ISO two-letter language code,
 * with an optional ISO country code, separated with an underscore
 * (e.g., "fr" for French, "fr_CA" for Canadian French). When no locale is
 * specified, the default is "en_US" (US English).</p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.CurrentDateTagger"
 *     toField="(target field)"
 *     format="(date format)"
 *     locale="(locale)"
 *     {@nx.include com.norconex.commons.lang.map.PropertySetter#attributes}>
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="CurrentDateTagger"
 *      toField="crawl_date" format="yyyy-MM-dd HH:mm" />
 * }
 * <p>
 * The above will store the current date along with hours and minutes
 * in a "crawl_date" field.
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
public class CurrentDateTransformer
        implements ConfigurableDocHandler<CurrentDateTransformerConfig> {

    private final CurrentDateTransformerConfig configuration =
            new CurrentDateTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {

        var dateStr = nowAsString();
        var finalField = configuration.getToField();
        if (StringUtils.isBlank(finalField)) {
            finalField = CurrentDateTransformerConfig.DEFAULT_FIELD;
        }
        PropertySetter.orAppend(configuration.getOnSet()).apply(
                docCtx.metadata(), finalField, dateStr);
        return true;
    }

    private String nowAsString() {
        if (StringUtils.isBlank(configuration.getFormat())) {
            return Long.toString(System.currentTimeMillis());
        }
        var safeLocale = configuration.getLocale();
        if (safeLocale == null) {
            safeLocale = Locale.US;
        }

        return ZonedDateTime.now(ZoneOffset.UTC)
                .format(
                        DateTimeFormatter.ofPattern(
                                configuration.getFormat(), safeLocale));
    }
}
