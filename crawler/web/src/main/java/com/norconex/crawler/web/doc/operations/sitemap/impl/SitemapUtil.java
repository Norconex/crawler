/* Copyright 2023-2026 Norconex Inc.
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
package com.norconex.crawler.web.doc.operations.sitemap.impl;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.file.ContentType;
import com.norconex.importer.doc.Doc;

import lombok.extern.slf4j.Slf4j;

@Slf4j
final class SitemapUtil {

    private SitemapUtil() {
    }

    static ZonedDateTime toDateTime(String value) {
        ZonedDateTime zdt = null;
        if (StringUtils.isBlank(value)) {
            return zdt;
        }
        try {
            if (value.contains("T")) {
                // has time
                zdt = ZonedDateTime.parse(value);
            } else {
                // has no time
                zdt = ZonedDateTime.of(
                        LocalDate.parse(value),
                        LocalTime.MIDNIGHT, ZoneOffset.UTC);
            }
        } catch (Exception e) {
            LOG.info("Invalid sitemap date: {}", value);
        }
        return zdt;
    }

    static InputStream uncompressedSitemapStream(Doc doc)
            throws IOException {
        InputStream is = doc.getInputStream();
        Optional<String> contentType = Optional
                .ofNullable(doc.getContentType())
                .map(ContentType::toString);
        if (contentType.isPresent() && (contentType.get().endsWith("gzip")
                || doc.getReference().endsWith(".gz"))) {
            is = new GZIPInputStream(is);
        }
        return is;
    }
}
