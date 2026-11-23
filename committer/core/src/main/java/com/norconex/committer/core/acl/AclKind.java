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
 * What a principal of an {@link Acl} is.
 */
public enum AclKind {
    /** A person (or an account acting as one). */
    USER,
    /** A group of principals. */
    GROUP,
    /** A role, granted to principals by the source system. */
    ROLE,
    /**
     * A principal with a reserved meaning, such as everyone. Its id is one
     * of the {@code SPECIAL_*} constants of {@link AclEntry}.
     */
    SPECIAL,
    /**
     * The source gives the principal's id without saying what kind of
     * principal it is (CMIS does this). Matched against users and groups
     * alike.
     */
    UNDEFINED;

    @JsonValue
    String jsonValue() {
        return name().toLowerCase();
    }
}
