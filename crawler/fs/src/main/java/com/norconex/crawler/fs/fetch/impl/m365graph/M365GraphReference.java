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
package com.norconex.crawler.fs.fetch.impl.m365graph;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

record M365GraphReference(
        Kind kind,
        Mode mode,
        String tenantId,
        String siteId,
        String siteUrl,
        String userId,
        String driveId,
        String itemId) {

    enum Kind {
        SITE,
        SITE_URL,
        USER,
        DRIVE,
        ITEM
    }

    enum Mode {
        SHAREPOINT,
        ONEDRIVE
    }

    static M365GraphReference parse(String reference) {
        var uri = URI.create(reference);
        var scheme = StringUtils.lowerCase(uri.getScheme(), Locale.ROOT);
        var tenantId = uri.getHost();
        if (StringUtils.isBlank(tenantId)) {
            throw new IllegalArgumentException(
                    "Reference tenant must be supplied in URI host: "
                            + reference);
        }

        var path = StringUtils.stripStart(
                StringUtils.defaultString(uri.getPath()), "/");
        var parts = StringUtils.isBlank(path)
                ? new String[0]
                : path.split("/");

        if ("m365sp".equals(scheme)) {
            if (parts.length == 2 && "sites".equals(parts[0])) {
                return new M365GraphReference(
                        Kind.SITE,
                        Mode.SHAREPOINT,
                        tenantId,
                        parts[1],
                        null,
                        null,
                        null,
                        null);
            }

            if (parts.length >= 1 && "siteurl".equals(parts[0])) {
                var siteUrl = parts.length == 2
                        ? parts[1]
                        : extractQueryValue(uri, "url");
                if (StringUtils.isBlank(siteUrl)) {
                    throw new IllegalArgumentException(
                            "Invalid SharePoint site URL reference: "
                                    + reference);
                }
                return new M365GraphReference(
                        Kind.SITE_URL,
                        Mode.SHAREPOINT,
                        tenantId,
                        null,
                        siteUrl,
                        null,
                        null,
                        null);
            }

            if (parts.length == 4
                    && "sites".equals(parts[0])
                    && "drives".equals(parts[2])) {
                return new M365GraphReference(
                        Kind.DRIVE,
                        Mode.SHAREPOINT,
                        tenantId,
                        parts[1],
                        null,
                        null,
                        parts[3],
                        null);
            }

            if (parts.length == 6
                    && "sites".equals(parts[0])
                    && "drives".equals(parts[2])
                    && "items".equals(parts[4])) {
                return new M365GraphReference(
                        Kind.ITEM,
                        Mode.SHAREPOINT,
                        tenantId,
                        parts[1],
                        null,
                        null,
                        parts[3],
                        parts[5]);
            }
            throw new IllegalArgumentException(
                    "Invalid SharePoint reference: " + reference);
        }

        if ("m365od".equals(scheme)) {
            if (parts.length == 2 && "users".equals(parts[0])) {
                return new M365GraphReference(
                        Kind.USER,
                        Mode.ONEDRIVE,
                        tenantId,
                        null,
                        null,
                        parts[1],
                        null,
                        null);
            }

            if (parts.length == 4
                    && "users".equals(parts[0])
                    && "drives".equals(parts[2])) {
                return new M365GraphReference(
                        Kind.DRIVE,
                        Mode.ONEDRIVE,
                        tenantId,
                        null,
                        null,
                        parts[1],
                        parts[3],
                        null);
            }

            if (parts.length == 6
                    && "users".equals(parts[0])
                    && "drives".equals(parts[2])
                    && "items".equals(parts[4])) {
                return new M365GraphReference(
                        Kind.ITEM,
                        Mode.ONEDRIVE,
                        tenantId,
                        null,
                        null,
                        parts[1],
                        parts[3],
                        parts[5]);
            }
            throw new IllegalArgumentException(
                    "Invalid OneDrive reference: " + reference);
        }

        throw new IllegalArgumentException(
                "Unsupported M365 scheme in reference: " + reference);
    }

    String toReference() {
        if (kind == Kind.SITE) {
            return "m365sp://%s/sites/%s".formatted(tenantId, siteId);
        }
        if (kind == Kind.SITE_URL) {
            return "m365sp://%s/siteurl?url=%s".formatted(
                    tenantId,
                    URLEncoder.encode(
                            StringUtils.defaultString(siteUrl),
                            StandardCharsets.UTF_8));
        }
        if (kind == Kind.USER) {
            return "m365od://%s/users/%s".formatted(tenantId, userId);
        }
        if (kind == Kind.DRIVE && mode == Mode.SHAREPOINT) {
            return "m365sp://%s/sites/%s/drives/%s".formatted(
                    tenantId, siteId, driveId);
        }
        if (kind == Kind.DRIVE) {
            return "m365od://%s/users/%s/drives/%s".formatted(
                    tenantId, userId, driveId);
        }
        if (mode == Mode.SHAREPOINT) {
            return "m365sp://%s/sites/%s/drives/%s/items/%s".formatted(
                    tenantId, siteId, driveId, itemId);
        }
        return "m365od://%s/users/%s/drives/%s/items/%s".formatted(
                tenantId, userId, driveId, itemId);
    }

    String itemApiPath() {
        if (kind != Kind.ITEM) {
            throw new IllegalStateException(
                    "Reference is not an item: " + toReference());
        }
        if (mode == Mode.SHAREPOINT) {
            return "/sites/%s/drives/%s/items/%s".formatted(
                    siteId, driveId, itemId);
        }
        return "/users/%s/drives/%s/items/%s".formatted(
                userId, driveId, itemId);
    }

    String driveRootApiPath() {
        if (kind != Kind.DRIVE) {
            throw new IllegalStateException(
                    "Reference is not a drive: " + toReference());
        }
        if (mode == Mode.SHAREPOINT) {
            return "/sites/%s/drives/%s/root".formatted(siteId, driveId);
        }
        return "/users/%s/drives/%s/root".formatted(userId, driveId);
    }

    String drivesApiPath() {
        if (kind == Kind.SITE) {
            return "/sites/%s/drives".formatted(siteId);
        }
        if (kind == Kind.USER) {
            return "/users/%s/drives".formatted(userId);
        }
        throw new IllegalStateException(
                "Reference has no drives API path: " + toReference());
    }

    String resolveSiteApiPath() {
        if (kind != Kind.SITE_URL) {
            throw new IllegalStateException(
                    "Reference is not a site URL: " + toReference());
        }
        var siteUri = URI.create(siteUrl);
        var host = siteUri.getHost();
        if (StringUtils.isBlank(host)) {
            throw new IllegalArgumentException(
                    "Invalid SharePoint site URL: " + siteUrl);
        }
        var serverPath = StringUtils.defaultIfBlank(siteUri.getPath(), "/");
        return "/sites/%s:%s".formatted(host, serverPath);
    }

    M365GraphReference child(String childItemId) {
        if (kind != Kind.DRIVE && kind != Kind.ITEM) {
            throw new IllegalStateException(
                    "Cannot create child item from: " + toReference());
        }
        return new M365GraphReference(
                Kind.ITEM,
                mode,
                tenantId,
                siteId,
                null,
                userId,
                driveId,
                childItemId);
    }

    M365GraphReference drive(String childDriveId) {
        if (kind != Kind.SITE && kind != Kind.USER) {
            throw new IllegalStateException(
                    "Cannot create drive reference from: " + toReference());
        }
        return new M365GraphReference(
                Kind.DRIVE,
                mode,
                tenantId,
                siteId,
                null,
                userId,
                childDriveId,
                null);
    }

    M365GraphReference site(String resolvedSiteId) {
        if (kind != Kind.SITE_URL) {
            throw new IllegalStateException(
                    "Cannot create site reference from: " + toReference());
        }
        return new M365GraphReference(
                Kind.SITE,
                Mode.SHAREPOINT,
                tenantId,
                resolvedSiteId,
                null,
                null,
                null,
                null);
    }

    boolean isDiscoveryEntry() {
        return kind == Kind.SITE || kind == Kind.SITE_URL || kind == Kind.USER;
    }

    private static String extractQueryValue(URI uri, String key) {
        var query = uri.getRawQuery();
        if (StringUtils.isBlank(query)) {
            return null;
        }
        for (String pair : query.split("&")) {
            var idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            var k = pair.substring(0, idx);
            var v = pair.substring(idx + 1);
            if (StringUtils.equals(k, key)) {
                return URLDecoder.decode(v, StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
