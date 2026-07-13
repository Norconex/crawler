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
package com.norconex.crawler.fs.fetch.impl;

import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.norconex.crawler.core.fetch.FetchRequest;
import com.norconex.importer.doc.Doc;

public final class FileFetchUtil {

    private FileFetchUtil() {
    }

    /**
     * Whether a reference starts with any of the given prefixes (typically
     * URI schemes).
     * @param req file fetch request
     * @param prefixes prefixes to compare
     * @return <code>true</code> if the request reference starts with one
     *     of the supplied prefixes
     */
    public static boolean referenceStartsWith(
            FetchRequest req, String... prefixes) {

        return Optional.ofNullable(req)
                .map(FetchRequest::getDoc)
                .map(Doc::getReference)
                .map(String::toLowerCase)
                .filter(ref -> Strings.CS.startsWithAny(ref, prefixes))
                .isPresent();
    }

    /**
     * <p>
     * Ensures paths to local files can be converted to valid URIs
     * by properly encoding each path segments. Non local files are returned
     * unchanged.
     * </p>
     * <p>
     * We consider a path to be a local file path (absolute or relative)
     * if it matches any of these conditions:
     *     - no scheme
     *     - scheme is "file"
     *     - scheme is one letter (e.g., windows drive letter)
     * </p>
     * @param path the path to encode
     * @return encode encoded path
     */
    public static String uriEncodeLocalPath(String path) {
        if (path == null) {
            return null;
        }

        // Extract URI scheme if present (only valid schemes at the beginning)
        var schemeMatch =
                Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.-]*):").matcher(path);
        String scheme = null;
        int schemeEnd = 0;
        if (schemeMatch.find()) {
            scheme = schemeMatch.group(1);
            schemeEnd = schemeMatch.end();
        }

        // For local paths (no scheme or "file" or single-letter Windows drive)
        if (scheme == null || "file".equalsIgnoreCase(scheme)
                || scheme.length() == 1) {
            // Only treat "/" and "\" as path separators, not ":"
            // This allows colons in filenames to be encoded
            var b = new StringBuilder();

            // Preserve the scheme and colon if present
            if (scheme != null) {
                b.append(scheme).append(":");
            }

            // Encode the path part
            var pathPart = path.substring(schemeEnd);
            var m = Pattern.compile("([^\\\\/]+|[\\\\/]+)").matcher(pathPart);
            while (m.find()) {
                if (StringUtils.containsAny(m.group(), "\\/")) {
                    b.append(m.group());
                } else {
                    b.append(uriEncodeSegment(m.group()));
                }
            }
            return b.toString();
        }
        return path;
    }

    private static String uriEncodeSegment(String value) {
        // Encode control characters and a handful of specific characters,
        // assuming all others are filename-valid on all major OSes.
        var b = new StringBuilder();
        for (char ch : value.toCharArray()) {
            if (ch >= 0 && ch <= 31 || ch == ' '
                    || "<>:;@#=&$,\"/\\|?*".indexOf(ch) > -1) {
                b.append(String.format("%%%02x", (int) ch));

            } else {
                b.append(ch);
            }
        }
        return b.toString();
    }
}
