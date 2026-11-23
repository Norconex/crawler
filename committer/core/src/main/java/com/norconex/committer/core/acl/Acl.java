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
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import lombok.NonNull;

/**
 * <p>
 * Who may read a document, in one shape whatever the source: a fetcher
 * that reads a source's permissions writes it on the document (see
 * {@link AclCodec}, and the {@value #FIELD} field), and a committer
 * translates it for its destination, or sends it as is.
 * </p>
 * <p>
 * A person may read the document only if every one of its {@link #levels()}
 * allows them. Most documents have one level; some sources check several,
 * such as a file share's permissions and then the file's own.
 * </p>
 *
 * @param version    the version of this shape, {@value #VERSION} for now;
 *                   a committer rejects a version it does not know
 * @param status     how much of the permissions this holds
 * @param scope      which of the source's permissions were asked for
 * @param levels     the checks to pass, in order; empty when the status is
 *                   {@link AclStatus#NONE}
 * @param owners     who owns the document (entries without access), for the
 *                   destinations that keep owners; not part of the access
 *                   decision
 * @param nativeData the source's own permission entries, untouched, for
 *                   audit or custom use; may be {@code null}
 */
@JsonInclude(Include.NON_EMPTY)
@JsonPropertyOrder(
    { "v", "status", "scope", "levels", "owners", "native" }
)
public record Acl(
        @JsonProperty("v") int version,
        @NonNull AclStatus status,
        @NonNull AclScope scope,
        List<AclLevel> levels,
        List<AclEntry> owners,
        @JsonProperty("native") Map<String, Object> nativeData) {

    /** The document field holding a document's ACL as JSON. */
    public static final String FIELD = "crawler.acl";
    /** The version of this shape that this code writes and reads. */
    public static final int VERSION = 1;

    /**
     * Copies the levels and the owners (none when {@code null}: an empty
     * list is not written), the owners without duplicates and in their
     * canonical order.
     * @param version    the version of this shape
     * @param status     how much of the permissions this holds
     * @param scope      which permissions were asked for
     * @param levels     the checks to pass, in order
     * @param owners     who owns the document
     * @param nativeData the source's own entries
     */
    public Acl {
        levels = levels == null ? List.of() : List.copyOf(levels);
        owners = owners == null
                ? List.of()
                : owners.stream().distinct().sorted(AclEntry.ORDER).toList();
    }

    /**
     * A complete ACL of everything the source has.
     * @param levels the checks to pass, in order
     * @param owners who owns the document
     * @param nativeData the source's own entries, or {@code null}
     * @return the ACL
     */
    public static Acl complete(
            List<AclLevel> levels,
            List<AclEntry> owners,
            Map<String, Object> nativeData) {
        return new Acl(VERSION, AclStatus.COMPLETE, AclScope.FULL, levels,
                owners, nativeData);
    }

    /**
     * An ACL for a document whose permissions were asked for and could not be
     * read at all.
     * @param scope which permissions were asked for
     * @return the ACL
     */
    public static Acl none(AclScope scope) {
        return new Acl(VERSION, AclStatus.NONE, scope, List.of(), List.of(),
                null);
    }

    /**
     * @param status the status to give
     * @return a copy of this ACL with another status
     */
    public Acl withStatus(AclStatus status) {
        return new Acl(version, status, scope, levels, owners, nativeData);
    }

    /**
     * @param scope the scope to give
     * @return a copy of this ACL with another scope
     */
    public Acl withScope(AclScope scope) {
        return new Acl(version, status, scope, levels, owners, nativeData);
    }
}
