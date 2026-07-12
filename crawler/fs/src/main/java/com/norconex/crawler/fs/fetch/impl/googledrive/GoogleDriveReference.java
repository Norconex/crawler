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
package com.norconex.crawler.fs.fetch.impl.googledrive;

import java.net.URI;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

record GoogleDriveReference(
        Kind kind,
        String workspaceId,
        String userId,
        String driveId,
        String itemId) {

    enum Kind {
        USER,
        DRIVE,
        ITEM
    }

    static GoogleDriveReference parse(String reference) {
        var uri = URI.create(reference);
        var scheme = StringUtils.lowerCase(uri.getScheme(), Locale.ROOT);
        if (!"gdrive".equals(scheme)) {
            throw new IllegalArgumentException(
                    "Unsupported Google Drive scheme in reference: "
                            + reference);
        }

        var workspaceId = uri.getHost();
        if (StringUtils.isBlank(workspaceId)) {
            throw new IllegalArgumentException(
                    "Reference workspaceId must be supplied in URI host: "
                            + reference);
        }

        var path = StringUtils.stripStart(
                StringUtils.defaultString(uri.getPath()), "/");
        var parts = StringUtils.isBlank(path)
                ? new String[0]
                : path.split("/");

        if (parts.length == 2 && "users".equals(parts[0])) {
            return new GoogleDriveReference(
                    Kind.USER,
                    workspaceId,
                    parts[1],
                    null,
                    null);
        }

        if (parts.length == 4
                && "users".equals(parts[0])
                && "items".equals(parts[2])) {
            return new GoogleDriveReference(
                    Kind.ITEM,
                    workspaceId,
                    parts[1],
                    null,
                    parts[3]);
        }

        if (parts.length == 2 && "drives".equals(parts[0])) {
            return new GoogleDriveReference(
                    Kind.DRIVE,
                    workspaceId,
                    null,
                    parts[1],
                    null);
        }

        if (parts.length == 4
                && "drives".equals(parts[0])
                && "items".equals(parts[2])) {
            return new GoogleDriveReference(
                    Kind.ITEM,
                    workspaceId,
                    null,
                    parts[1],
                    parts[3]);
        }

        throw new IllegalArgumentException(
                "Invalid Google Drive reference: " + reference);
    }

    String toReference() {
        if (kind == Kind.USER) {
            return "gdrive://%s/users/%s".formatted(workspaceId, userId);
        }
        if (kind == Kind.DRIVE) {
            return "gdrive://%s/drives/%s".formatted(workspaceId, driveId);
        }
        if (StringUtils.isNotBlank(userId)) {
            return "gdrive://%s/users/%s/items/%s".formatted(
                    workspaceId, userId, itemId);
        }
        return "gdrive://%s/drives/%s/items/%s".formatted(
                workspaceId, driveId, itemId);
    }

    String itemApiPath() {
        if (kind != Kind.ITEM) {
            throw new IllegalStateException(
                    "Reference is not an item: " + toReference());
        }
        return "/files/%s".formatted(itemId);
    }

    GoogleDriveReference child(String childItemId) {
        if (kind != Kind.USER && kind != Kind.DRIVE && kind != Kind.ITEM) {
            throw new IllegalStateException(
                    "Cannot create child item from: " + toReference());
        }
        return new GoogleDriveReference(
                Kind.ITEM,
                workspaceId,
                userId,
                driveId,
                childItemId);
    }

    boolean isDiscoveryEntry() {
        return kind == Kind.USER || kind == Kind.DRIVE;
    }
}
