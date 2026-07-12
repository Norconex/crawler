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
package com.norconex.crawler.fs.fetch.impl.box;

import java.net.URI;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

record BoxReference(
        Kind kind,
        String enterpriseId,
        String folderId,
        String itemId) {

    enum Kind {
        FOLDER,
        ITEM
    }

    static BoxReference parse(String reference) {
        var uri = URI.create(reference);
        var scheme = StringUtils.lowerCase(uri.getScheme(), Locale.ROOT);
        if (!"box".equals(scheme)) {
            throw new IllegalArgumentException(
                    "Unsupported Box scheme in reference: " + reference);
        }

        var enterpriseId = uri.getHost();
        if (StringUtils.isBlank(enterpriseId)) {
            throw new IllegalArgumentException(
                    "Reference enterpriseId must be supplied in URI host: "
                            + reference);
        }

        var path = StringUtils.stripStart(
                StringUtils.defaultString(uri.getPath()), "/");
        var parts = StringUtils.isBlank(path)
                ? new String[0]
                : path.split("/");

        if (parts.length == 2 && "folders".equals(parts[0])) {
            return new BoxReference(Kind.FOLDER, enterpriseId, parts[1], null);
        }

        if (parts.length == 4
                && "folders".equals(parts[0])
                && "items".equals(parts[2])) {
            return new BoxReference(
                    Kind.ITEM,
                    enterpriseId,
                    parts[1],
                    parts[3]);
        }

        throw new IllegalArgumentException(
                "Invalid Box reference: " + reference);
    }

    String toReference() {
        if (kind == Kind.FOLDER) {
            return "box://%s/folders/%s".formatted(enterpriseId, folderId);
        }
        return "box://%s/folders/%s/items/%s".formatted(
                enterpriseId,
                folderId,
                itemId);
    }

    String itemFileApiPath() {
        if (kind != Kind.ITEM) {
            throw new IllegalStateException(
                    "Reference is not an item: " + toReference());
        }
        return "/files/%s".formatted(itemId);
    }

    String itemFolderApiPath() {
        if (kind != Kind.ITEM) {
            throw new IllegalStateException(
                    "Reference is not an item: " + toReference());
        }
        return "/folders/%s".formatted(itemId);
    }

    String containerFolderApiPath() {
        if (kind == Kind.FOLDER) {
            return "/folders/%s".formatted(folderId);
        }
        return "/folders/%s".formatted(itemId);
    }

    BoxReference child(String childItemId) {
        if (kind != Kind.FOLDER && kind != Kind.ITEM) {
            throw new IllegalStateException(
                    "Cannot create child item from: " + toReference());
        }
        return new BoxReference(
                Kind.ITEM,
                enterpriseId,
                kind == Kind.FOLDER ? folderId : itemId,
                childItemId);
    }

    boolean isDiscoveryEntry() {
        return kind == Kind.FOLDER && "0".equals(folderId);
    }
}
