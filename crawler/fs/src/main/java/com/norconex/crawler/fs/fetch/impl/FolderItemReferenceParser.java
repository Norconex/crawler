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

import java.net.URI;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

public final class FolderItemReferenceParser {

    public enum Kind {
        FOLDER,
        ITEM
    }

    public record Parsed(Kind kind, String host, String folderId,
            String itemId) {
    }

    private FolderItemReferenceParser() {
    }

    public static Parsed parse(
            String reference,
            String expectedScheme,
            String serviceName,
            String hostLabel) {
        var uri = URI.create(reference);
        var scheme = StringUtils.lowerCase(uri.getScheme(), Locale.ROOT);
        if (!expectedScheme.equals(scheme)) {
            throw new IllegalArgumentException(
                    "Unsupported %s scheme in reference: %s"
                            .formatted(serviceName, reference));
        }

        var host = uri.getHost();
        if (StringUtils.isBlank(host)) {
            throw new IllegalArgumentException(
                    "Reference %s must be supplied in URI host: %s"
                            .formatted(hostLabel, reference));
        }

        var path = StringUtils.stripStart(
                StringUtils.defaultString(uri.getPath()), "/");
        var parts = StringUtils.isBlank(path)
                ? new String[0]
                : path.split("/");

        if (parts.length == 2 && "folders".equals(parts[0])) {
            return new Parsed(Kind.FOLDER, host, parts[1], null);
        }

        if (parts.length == 4
                && "folders".equals(parts[0])
                && "items".equals(parts[2])) {
            return new Parsed(Kind.ITEM, host, parts[1], parts[3]);
        }

        throw new IllegalArgumentException(
                "Invalid %s reference: %s".formatted(serviceName, reference));
    }
}
