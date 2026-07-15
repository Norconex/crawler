/* Copyright 2026 Norconex Inc.
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
package com.norconex.crawler.fs.fetch.impl;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.map.Properties;

/**
 * Small shared helpers used by multiple fetcher implementations.
 */
public final class FetcherSupport {

    private FetcherSupport() {
    }

    public static String urlEncode(String value) {
        return URLEncoder.encode(
                StringUtils.defaultString(value),
                StandardCharsets.UTF_8);
    }

    public static String decodeUtf8ErrorBody(byte[] body) {
        return StringUtils.abbreviate(
                new String(body == null ? new byte[0] : body,
                        StandardCharsets.UTF_8),
                512);
    }

    public static void setIsoTimestamp(
            Properties meta,
            String metaPrefix,
            String field,
            String value) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        try {
            meta.set(field,
                    ZonedDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME)
                            .toInstant().toEpochMilli());
        } catch (DateTimeParseException e) {
            meta.set(metaPrefix + "invalidDate." + field, value);
        }
    }

    public static String firstNonBlank(String... values) {
        for (String value : values) {
            var trimmed = StringUtils.trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }
}
