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

import com.norconex.crawler.fs.fetch.acl.AclFetchMode;

import lombok.Data;
import lombok.NonNull;
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
 * <p>
 * A SAS token can be given in {@link #setSasToken(String)}, or as the
 * password of the credentials when they have no username. The latter is
 * the way to keep the token secret: like any credentials password, it can
 * be encrypted.
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
     * Optional SAS (Shared Access Signature) token used instead of shared-key
     * credentials. It is kept as is in the configuration. To keep it secret
     * (encrypted, or supplied as a variable), leave this empty and give it
     * as the password of the credentials, with no username.
     */
    private String sasToken;

    /**
     * Which permissions to read and write on each document as its access
     * control list (ACL): the owner, owning group and their rights
     * ({@link AclFetchMode#BASIC}), plus the named users and groups of the
     * path's ACL and its mask ({@link AclFetchMode#EXTENDED}). Default is
     * {@link AclFetchMode#NONE}.
     */
    @NonNull
    private AclFetchMode acl = AclFetchMode.NONE;
}
