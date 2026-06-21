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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.commons.lang.time.ZonedDateTimeParser;

import lombok.Data;
import lombok.experimental.Accessors;
import lombok.experimental.FieldNameConstants;

/**
 * <p>Formats a date from any given format to a format of choice, as per the
 * formatting options found on {@link ZonedDateTimeParser} with the addition
 * of the format "EPOCH", which represents the difference, measured in
 * milliseconds, between the date and midnight, January 1, 1970.
 * The default format
 * for <code>fromFormat</code> or <code>toFormat</code> when not specified
 * is EPOCH.</p>
 *
 * <p>When omitting the <code>toField</code>, the value will replace the one
 * in the same field.</p>
 *
 * <h2>Storing values in an existing field</h2>
 * <p>
 * If a target field with the same name already exists for a document,
 * values will be added to the end of the existing value list.
 * It is possible to change this default behavior
 * with {@link #setOnSet(PropertySetter)}.
 * </p>
 *
 * <p>Can be used both as a pre-parse or post-parse handler.</p>
 *
 * <p>It is possible to specify a locale used for parsing and formatting dates.
 * The locale is the ISO two-letter language code, with an optional
 * ISO country code, separated with an underscore (e.g., "fr" for French,
 * "fr_CA" for Canadian French). When no locale is specified, the default is
 * English.</p>
 *
 * <p>
 * Multiple <code>fromFormat</code> values can be specified. Each formats will
 * be tried in the order provided and the first format that succeed in
 * parsing a date will be used.
 * A date will be considered "bad" only if none of the formats could parse the
 * date.
 * </p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.DateFormatTagger"
 *     fromField="(from field)" toField="(to field)"
 *     fromLocale="(locale)"    toLocale="(locale)"
 *     toFormat="(date format)"
 *     keepBadDates="(false|true)"
 *     {@nx.include com.norconex.commons.lang.map.PropertySetter#attributes}>
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <!-- multiple "fromFormat" tags allowed (only one needs to match) -->
 *   <fromFormat>(date format)</fromFormat>
 * </handler>
 * }
 *
 * {@nx.xml.example
 *  <handler class="DateFormatTagger"
 *          fromField="Last-Modified"
 *          toField="solr_date"
 *          toFormat="yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" >
 *      <fromFormat>EEE, dd MMM yyyy HH:mm:ss zzz</fromFormat>
 *      <fromFormat>EPOCH</fromFormat>
 *  </handler>
 * }
 * <p>
 * The following converts a date that is sometimes obtained from the
 * HTTP header "Last-Modified" and sometimes is an EPOCH date,
 * into an Apache Solr date format:
 * </p>
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
@FieldNameConstants
public class DateFormatTransformerConfig {

    private String fromField;
    private String toField;
    private final List<String> fromFormats = new ArrayList<>();
    private String toFormat;
    /**
     * The locale used for parsing the source date.
     * @param fromLocale locale
     * @return locale
     */
    private Locale fromLocale;
    /**
     * The locale used for formatting the target date.
     * @param toLocale locale
     * @return locale
     */
    private Locale toLocale;
    private boolean keepBadDates;
    /**
     * The property setter to use when a value is set.
     * @param onSet property setter
     * @return property setter
     */
    private PropertySetter onSet;

    /**
     * Gets the source date formats to match.
     * @return source date formats
     */
    public List<String> getFromFormats() {
        return Collections.unmodifiableList(fromFormats);
    }

    /**
     * Sets the source date formats to match.
     * @param fromFormats source date formats
     */
    public DateFormatTransformerConfig setFromFormats(
            List<String> fromFormats) {
        CollectionUtil.setAll(this.fromFormats, fromFormats);
        return this;
    }
}
