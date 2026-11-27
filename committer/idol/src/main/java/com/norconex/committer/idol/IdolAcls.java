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
package com.norconex.committer.idol;

import java.util.List;
import java.util.stream.Collectors;

import com.norconex.committer.core.acl.Acl;
import com.norconex.committer.core.acl.AclAccess;
import com.norconex.committer.core.acl.AclEntry;
import com.norconex.committer.core.acl.AclKind;

/**
 * Writes a document's standard {@link Acl} as the text IDOL's mapped
 * security reads (see {@link IdolAclSyntax}). IDOL checks one list of users
 * and groups, so an ACL it cannot express (several levels, a role, a denial
 * in the simple syntax) is reported by {@link #problem(Acl, IdolAclSyntax)}
 * rather than approximated.
 */
final class IdolAcls {

    private IdolAcls() {
    }

    /**
     * @param acl    the document's ACL
     * @param syntax the syntax to write
     * @return why IDOL cannot express it, or {@code null} if it can
     */
    static String problem(Acl acl, IdolAclSyntax syntax) {
        if (acl.levels().size() != 1) {
            return "IDOL takes a single level of permissions and this "
                    + "document has " + acl.levels().size();
        }
        for (var entry : acl.levels().get(0).entries()) {
            var why = entryProblem(entry, syntax);
            if (why != null) {
                return why;
            }
        }
        return null;
    }

    private static String entryProblem(AclEntry entry, IdolAclSyntax syntax) {
        var denied = entry.access() == AclAccess.DENY;
        var simple = syntax == IdolAclSyntax.SIMPLE;
        if (entry.kind() == AclKind.SPECIAL) {
            return specialProblem(entry, denied, simple);
        }
        if (simple && denied) {
            return "the simple IDOL syntax cannot express the denial of "
                    + entry.principalText();
        }
        if (entry.kind() == AclKind.ROLE) {
            return "IDOL has no equivalent for the role "
                    + entry.principalText();
        }
        return entry.id().contains(",")
                ? entry.principalText() + " has a comma in its name"
                : null;
    }

    private static String specialProblem(
            AclEntry entry, boolean denied, boolean simple) {
        if (simple) {
            return "the simple IDOL syntax cannot express "
                    + entry.principalText();
        }
        if (!List.of(AclEntry.SPECIAL_EVERYONE, AclEntry.SPECIAL_AUTHENTICATED)
                .contains(entry.id())) {
            return "IDOL has no equivalent for " + entry.principalText();
        }
        return denied ? "IDOL cannot deny everyone" : null;
    }

    /**
     * @param acl    the document's ACL, for which {@link #problem} is
     *               {@code null}
     * @param syntax the syntax to write
     * @return the text of the access control list field
     */
    static String write(Acl acl, IdolAclSyntax syntax) {
        var entries = acl.levels().get(0).entries();
        var core = ":U:" + names(entries, false, false)
                + ":G:" + names(entries, true, false);
        if (syntax == IdolAclSyntax.SIMPLE) {
            return core;
        }
        var everyone = entries.stream()
                .anyMatch(e -> e.kind() == AclKind.SPECIAL);
        return (everyone ? "1" : "0") + core
                + ":NU:" + names(entries, false, true)
                + ":NG:" + names(entries, true, true);
    }

    /**
     * The ids of the users or of the groups, allowed or denied, separated by
     * commas. An id of unknown kind is matched against users and groups, so
     * it is in both.
     */
    private static String names(
            List<AclEntry> entries, boolean groups, boolean denied) {
        var wanted = groups ? AclKind.USER : AclKind.GROUP;
        return entries.stream()
                .filter(e -> e.kind() != AclKind.SPECIAL
                        && e.kind() != wanted
                        && (e.access() == AclAccess.DENY) == denied)
                .map(AclEntry::id)
                .collect(Collectors.joining(","));
    }
}
