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
package com.norconex.crawler.fs.fetch.impl.egnyte;

import com.norconex.crawler.fs.fetch.impl.FolderItemReferenceParser;

record EgnyteReference(
        Kind kind,
        String domain,
        String folderId,
        String itemId) {

    enum Kind {
        FOLDER,
        ITEM
    }

    static EgnyteReference parse(String reference) {
        var parsed = FolderItemReferenceParser.parse(
                reference,
                "egnyte",
                "Egnyte",
                "Egnyte domain");
        return new EgnyteReference(
                parsed.kind() == FolderItemReferenceParser.Kind.FOLDER
                        ? Kind.FOLDER
                        : Kind.ITEM,
                parsed.host(),
                parsed.folderId(),
                parsed.itemId());
    }

    String toReference() {
        if (kind == Kind.FOLDER) {
            return "egnyte://%s/folders/%s".formatted(domain, folderId);
        }
        return "egnyte://%s/folders/%s/items/%s".formatted(
                domain,
                folderId,
                itemId);
    }

    String itemFileApiPath() {
        if (kind != Kind.ITEM) {
            throw new IllegalStateException(
                    "Reference is not an item: " + toReference());
        }
        return "/fs-ids/%s".formatted(itemId);
    }

    String itemFolderApiPath() {
        if (kind != Kind.ITEM) {
            throw new IllegalStateException(
                    "Reference is not an item: " + toReference());
        }
        return "/fs-folders/%s".formatted(itemId);
    }

    String containerFolderApiPath() {
        if (kind == Kind.FOLDER) {
            return "/fs-folders/%s".formatted(folderId);
        }
        return "/fs-folders/%s".formatted(itemId);
    }

    EgnyteReference child(String childItemId) {
        if (kind != Kind.FOLDER && kind != Kind.ITEM) {
            throw new IllegalStateException(
                    "Cannot create child item from: " + toReference());
        }
        return new EgnyteReference(
                Kind.ITEM,
                domain,
                kind == Kind.FOLDER ? folderId : itemId,
                childItemId);
    }

    boolean isDiscoveryEntry() {
        return kind == Kind.FOLDER && "root".equals(folderId);
    }
}
