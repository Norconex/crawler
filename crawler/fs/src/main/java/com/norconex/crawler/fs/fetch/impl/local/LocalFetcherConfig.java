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
package com.norconex.crawler.fs.fetch.impl.local;

import com.norconex.crawler.core.fetch.BaseFetcherConfig;
import com.norconex.crawler.fs.fetch.acl.AclFetchMode;

import lombok.Data;
import lombok.NonNull;
import lombok.experimental.Accessors;

/**
 * <p>
 * Configuration for {@link LocalFetcher}.
 * </p>
 */
@Data
@Accessors(chain = true)
public class LocalFetcherConfig extends BaseFetcherConfig {

    /**
     * Which permissions to read and write on each document as its access
     * control list (ACL). On Windows, the file's ACL; on Linux and macOS,
     * the owner, group and mode bits ({@link AclFetchMode#BASIC}), plus the
     * file's extended ACL ({@link AclFetchMode#EXTENDED}). Default is
     * {@link AclFetchMode#NONE}.
     */
    @NonNull
    private AclFetchMode acl = AclFetchMode.NONE;

}
