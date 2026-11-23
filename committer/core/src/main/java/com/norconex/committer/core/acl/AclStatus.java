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
package com.norconex.committer.core.acl;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How much of a document's permissions an {@link Acl} holds.
 */
public enum AclStatus {
    /**
     * Everything that decides who may read the document, within the
     * {@link AclScope} asked for, was captured.
     */
    COMPLETE,
    /**
     * Some permissions could not be read or represented: the ACL may admit
     * too few or too many principals.
     */
    PARTIAL,
    /** Permissions were asked for, but none could be read. */
    NONE;

    @JsonValue
    String jsonValue() {
        return name().toLowerCase();
    }
}
