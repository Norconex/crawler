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

import com.norconex.crawler.fs.fetch.impl.BaseAuthNioFetcherConfig;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * Configuration for {@link AdlsGen2Fetcher}.
 * </p>
 * <p>
 * Shared-key authentication uses the configured credentials, where the
 * username is the Azure storage account name and the password is the
 * storage account key.
 * </p>
 */
@Data
@Accessors(chain = true)
public class AdlsGen2FetcherConfig extends BaseAuthNioFetcherConfig {

    /**
     * Optional service endpoint override. When omitted, the endpoint is
     * derived from the reference account as
     * <code>https://&lt;account&gt;.dfs.core.windows.net</code>.
     */
    private String endpoint;

    /**
     * Optional SAS token used instead of shared-key credentials.
     */
    private String sasToken;

    /**
     * Whether ACL, owner, and group metadata extraction is disabled.
     */
    private boolean aclDisabled;
}
