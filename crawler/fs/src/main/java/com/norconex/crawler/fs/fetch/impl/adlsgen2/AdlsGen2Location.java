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
package com.norconex.crawler.fs.fetch.impl.adlsgen2;

import java.net.URI;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

record AdlsGen2Location(
        String scheme, String fileSystem, String account, String host,
        String path) {

    static AdlsGen2Location from(URI uri) {
        var fileSystem = uri.getUserInfo();
        if (StringUtils.isBlank(fileSystem)) {
            throw new IllegalArgumentException(
                    "ADLS Gen2 reference must include a file system in the "
                            + "authority, e.g. abfss://filesystem@account.dfs.core.windows.net/path");
        }

        var host = uri.getHost();
        if (StringUtils.isBlank(host)) {
            throw new IllegalArgumentException(
                    "ADLS Gen2 reference must include a storage account host.");
        }

        var account = StringUtils.removeEndIgnoreCase(
                host, ".dfs.core.windows.net");
        if (StringUtils.isBlank(account)) {
            account = host;
        }

        var path = StringUtils.defaultIfBlank(uri.getPath(), "/");
        return new AdlsGen2Location(
                StringUtils.defaultIfBlank(uri.getScheme(), "abfss")
                        .toLowerCase(Locale.ROOT),
                fileSystem,
                account,
                host,
                path);
    }

    String key() {
        return scheme + "://" + fileSystem + "@" + host;
    }
}
