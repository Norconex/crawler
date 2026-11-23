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
package com.norconex.crawler.fs.fetch.acl;

import com.norconex.committer.core.acl.Acl;
import com.norconex.committer.core.acl.AclScope;
import com.norconex.committer.core.acl.AclStatus;

/**
 * Which of a source's permissions a fetcher reads and writes on each
 * document, as an {@link Acl} in the {@value Acl#FIELD} field.
 */
public enum AclFetchMode {
    /** Permissions are not read, and no ACL is written. */
    NONE,
    /**
     * Only the basic permissions are read: for POSIX-style sources, the
     * owner, group and mode bits, without extended ACLs (no native call).
     * The ACL is marked {@link AclScope#BASIC} and is complete, as it holds
     * all that was asked for. Sources without such a distinction read the
     * same as with {@link #EXTENDED}.
     */
    BASIC,
    /**
     * Everything the source has is read. What cannot be read makes the ACL
     * {@link AclStatus#PARTIAL}.
     */
    EXTENDED;

    /**
     * @return {@code true} unless this is {@link #NONE}
     */
    public boolean isEnabled() {
        return this != NONE;
    }

    /**
     * @return the scope an ACL read this way has
     */
    public AclScope scope() {
        return this == BASIC ? AclScope.BASIC : AclScope.FULL;
    }
}
