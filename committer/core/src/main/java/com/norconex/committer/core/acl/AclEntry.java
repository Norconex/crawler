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

import static java.util.Comparator.comparing;
import static java.util.Comparator.naturalOrder;
import static java.util.Comparator.nullsFirst;

import java.util.Comparator;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import lombok.NonNull;

/**
 * One principal of an {@link Acl}, and whether it is allowed or denied
 * reading. As an owner (see {@link Acl#owners()}), {@code access} is
 * {@code null}.
 *
 * @param kind   what the principal is
 * @param dir    the directory the id belongs to, as
 *               {@code <system>:<name>} (for example {@code ad:CORP},
 *               {@code entra:<tenant id>}, {@code google:acme.com},
 *               {@code unix:<host>}, {@code source:<kind>} for groups that
 *               exist only inside the source); {@code null} for
 *               {@link AclKind#SPECIAL} principals
 * @param id     the principal's identifier in that directory: the most
 *               stable one the source gives (an object id or a SID rather
 *               than a name, when it gives both)
 * @param name   a readable name, when the source gives one besides the id;
 *               may be {@code null}
 * @param access allowed or denied; {@code null} for an owner
 */
@JsonInclude(Include.NON_NULL)
@JsonPropertyOrder({ "kind", "dir", "id", "name", "access" })
public record AclEntry(
        @NonNull AclKind kind,
        String dir,
        @NonNull String id,
        String name,
        AclAccess access) {

    /** Everybody, even anonymous users. */
    public static final String SPECIAL_EVERYONE = "everyone";
    /** Anybody signed in. */
    public static final String SPECIAL_AUTHENTICATED = "authenticated";
    /** Everybody in the organization that owns the document. */
    public static final String SPECIAL_DOMAIN = "domain";
    /** Anybody holding a sharing link to the document. */
    public static final String SPECIAL_LINK = "link";

    /**
     * The order entries are written in, so the same permissions always give
     * the same text (and the same checksum).
     */
    static final Comparator<AclEntry> ORDER =
            comparing(AclEntry::access, nullsFirst(naturalOrder()))
                    .thenComparing(AclEntry::kind)
                    .thenComparing(AclEntry::dir, nullsFirst(naturalOrder()))
                    .thenComparing(AclEntry::id)
                    .thenComparing(AclEntry::name,
                            nullsFirst(naturalOrder()));

    /**
     * An allowed principal.
     * @param kind what the principal is
     * @param dir  the directory it belongs to
     * @param id   its id in that directory
     * @return the entry
     */
    public static AclEntry allow(AclKind kind, String dir, String id) {
        return new AclEntry(kind, dir, id, null, AclAccess.ALLOW);
    }

    /**
     * A denied principal.
     * @param kind what the principal is
     * @param dir  the directory it belongs to
     * @param id   its id in that directory
     * @return the entry
     */
    public static AclEntry deny(AclKind kind, String dir, String id) {
        return new AclEntry(kind, dir, id, null, AclAccess.DENY);
    }

    /**
     * An allowed principal with a reserved meaning.
     * @param id one of the {@code SPECIAL_*} constants
     * @return the entry
     */
    public static AclEntry allowSpecial(String id) {
        return new AclEntry(AclKind.SPECIAL, null, id, null, AclAccess.ALLOW);
    }

    /**
     * An owner.
     * @param kind what the principal is
     * @param dir  the directory it belongs to
     * @param id   its id in that directory
     * @return the entry
     */
    public static AclEntry owner(AclKind kind, String dir, String id) {
        return new AclEntry(kind, dir, id, null, null);
    }

    /**
     * @param name a readable name
     * @return a copy of this entry with the given readable name
     */
    public AclEntry withName(String name) {
        return new AclEntry(kind, dir, id, name, access);
    }

    /**
     * This principal as one text value, the way flat fields carry it:
     * {@code kind:dir:id}, or {@code special:id}.
     * @return the principal as text
     */
    public String principalText() {
        var kindText = kind.name().toLowerCase();
        return dir == null
                ? kindText + ":" + id
                : kindText + ":" + dir + ":" + id;
    }
}
