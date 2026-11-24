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

import java.util.ArrayList;
import java.util.List;

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
            if (entry.access() == AclAccess.DENY
                    && syntax == IdolAclSyntax.SIMPLE) {
                return "the simple IDOL syntax cannot express the denial of "
                        + entry.principalText();
            }
            if (entry.kind() == AclKind.SPECIAL
                    && syntax == IdolAclSyntax.SIMPLE) {
                return "the simple IDOL syntax cannot express "
                        + entry.principalText();
            }
            if (entry.kind() == AclKind.ROLE) {
                return "IDOL has no equivalent for the role "
                        + entry.principalText();
            }
            if (entry.kind() == AclKind.SPECIAL && !isEveryone(entry)) {
                return "IDOL has no equivalent for "
                        + entry.principalText();
            }
            if (entry.kind() == AclKind.SPECIAL
                    && entry.access() == AclAccess.DENY) {
                return "IDOL cannot deny everyone";
            }
            if (entry.id().contains(",")) {
                return entry.principalText() + " has a comma in its name";
            }
        }
        return null;
    }

    /**
     * @param acl    the document's ACL, for which {@link #problem} is
     *               {@code null}
     * @param syntax the syntax to write
     * @return the text of the access control list field
     */
    static String write(Acl acl, IdolAclSyntax syntax) {
        var users = new ArrayList<String>();
        var groups = new ArrayList<String>();
        var noUsers = new ArrayList<String>();
        var noGroups = new ArrayList<String>();
        var everyone = false;
        for (var entry : acl.levels().get(0).entries()) {
            if (entry.kind() == AclKind.SPECIAL) {
                everyone = true;
                continue;
            }
            var denied = entry.access() == AclAccess.DENY;
            // An id of unknown kind is matched against users and groups.
            if (entry.kind() != AclKind.GROUP) {
                (denied ? noUsers : users).add(entry.id());
            }
            if (entry.kind() != AclKind.USER) {
                (denied ? noGroups : groups).add(entry.id());
            }
        }
        var core = ":U:" + String.join(",", users)
                + ":G:" + String.join(",", groups);
        return syntax == IdolAclSyntax.SIMPLE
                ? core
                : (everyone ? "1" : "0") + core
                        + ":NU:" + String.join(",", noUsers)
                        + ":NG:" + String.join(",", noGroups);
    }

    private static boolean isEveryone(AclEntry entry) {
        return List.of(AclEntry.SPECIAL_EVERYONE,
                AclEntry.SPECIAL_AUTHENTICATED).contains(entry.id());
    }
}
