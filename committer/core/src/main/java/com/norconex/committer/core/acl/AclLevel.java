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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import lombok.NonNull;

/**
 * One check a person must pass to read a document, such as a file share's
 * permissions or the file's own. A person may read a document only if every
 * level of its {@link Acl} allows them; within a level, a matching deny wins
 * over any allow, and a level that allows nobody admits nobody.
 *
 * @param name    a label for people reading the data (for example
 *                {@code share}, {@code path}, {@code file}); it has no effect
 *                on evaluation
 * @param entries who is allowed or denied at this level
 */
@JsonPropertyOrder({ "name", "entries" })
public record AclLevel(@NonNull String name, @NonNull List<AclEntry> entries) {

    /**
     * Copies the entries, without duplicates, in their canonical order.
     * @param name    the level's label
     * @param entries its entries
     */
    public AclLevel {
        entries = entries.stream().distinct().sorted(AclEntry.ORDER).toList();
    }
}
