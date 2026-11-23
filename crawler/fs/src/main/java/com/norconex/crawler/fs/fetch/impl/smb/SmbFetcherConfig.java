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
package com.norconex.crawler.fs.fetch.impl.smb;

import com.norconex.crawler.fs.fetch.impl.BaseAuthNioFetcherConfig;

import com.norconex.crawler.fs.fetch.acl.AclFetchMode;

import lombok.Data;
import lombok.NonNull;
import lombok.experimental.Accessors;

/**
 * <p>
 * Configuration for {@link SmbFetcher}.
 * </p>
 */
@Data
@Accessors(chain = true)
public class SmbFetcherConfig extends BaseAuthNioFetcherConfig {

    /**
     * Which permissions to read and write on each document as its access
     * control list (ACL): the file's own ({@link AclFetchMode#BASIC}), or
     * the share's too ({@link AclFetchMode#EXTENDED}), as a level of its
     * own, since a person must pass both. Reading a share's permissions
     * usually needs an account with administrative rights on the server;
     * without them, the documents' ACLs are partial. Default is
     * {@link AclFetchMode#NONE}.
     */
    @NonNull
    private AclFetchMode acl = AclFetchMode.NONE;

    /**
     * Whether the server is a Linux (or other Unix) server sharing its
     * files through Samba. Such a server also checks the right to go
     * through every folder above a file, which Windows lets everyone do, so
     * the ACL then gets a {@code path} level for the folders within the
     * share. Default is {@code false} (a Windows server).
     */
    private boolean linuxServer;
}
