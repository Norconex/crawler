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

/**
 * How a committer sends a document's {@link Acl}.
 */
public enum AclFormat {
    /**
     * As the destination's own access control. Only committers whose
     * destination has one support it.
     */
    NATIVE,
    /** As the JSON text of the {@value Acl#FIELD} field, untouched. */
    JSON,
    /**
     * As plain multi-valued fields, for destinations that cannot take JSON
     * (see {@link AclFlattener}).
     */
    FLAT,
    /** Not at all: the {@value Acl#FIELD} field is removed. */
    NONE
}
