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
package com.norconex.crawler.fs.fetch.impl.azureblob;

import java.net.URI;

import org.apache.commons.lang3.StringUtils;

record AzureBlobLocation(
        String scheme, String account, String container, String path) {

    static AzureBlobLocation from(URI uri) {
        var account = uri.getHost();
        if (StringUtils.isBlank(account)) {
            throw new IllegalArgumentException(
                    "Azure Blob reference must include an account host.");
        }

        var rawPath = StringUtils.defaultString(uri.getPath());
        var trimmedPath = StringUtils.removeStart(rawPath, "/");
        if (StringUtils.isBlank(trimmedPath)) {
            throw new IllegalArgumentException(
                    "Azure Blob reference must include a container path.");
        }

        var firstSlash = trimmedPath.indexOf('/');
        var container = firstSlash < 0
                ? trimmedPath
                : trimmedPath.substring(0, firstSlash);
        var blobPath = firstSlash < 0
                ? "/"
                : "/" + trimmedPath.substring(firstSlash + 1);

        return new AzureBlobLocation(
                StringUtils.defaultIfBlank(uri.getScheme(), "azblob"),
                account,
                container,
                StringUtils.defaultIfBlank(blobPath, "/"));
    }

    String key() {
        return account + "/" + container;
    }
}
