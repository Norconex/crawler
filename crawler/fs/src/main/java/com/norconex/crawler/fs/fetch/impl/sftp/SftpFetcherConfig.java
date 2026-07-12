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
package com.norconex.crawler.fs.fetch.impl.sftp;

import java.io.File;
import java.time.Duration;

import com.norconex.crawler.fs.fetch.impl.BaseAuthNioFetcherConfig;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * Configuration for {@link SftpFetcher}.
 * SFTP fetcher.
 * </p>
 */
@Data
@Accessors(chain = true)
public class SftpFetcherConfig extends BaseAuthNioFetcherConfig {

    private String compression;
    private String fileNameEncoding;
    private File knownHosts;
    private String preferredAuthentications;
    private String strictHostKeyChecking = "no";
    private Duration connectTimeout;
    private boolean userDirIsRoot;
}
