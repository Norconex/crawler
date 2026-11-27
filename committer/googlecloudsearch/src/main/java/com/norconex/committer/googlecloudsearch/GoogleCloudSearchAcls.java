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
package com.norconex.committer.googlecloudsearch;

import java.util.ArrayList;
import java.util.List;

import com.google.api.services.cloudsearch.v1.model.GSuitePrincipal;
import com.google.api.services.cloudsearch.v1.model.ItemAcl;
import com.google.api.services.cloudsearch.v1.model.Principal;
import com.norconex.committer.core.acl.Acl;
import com.norconex.committer.core.acl.AclAccess;
import com.norconex.committer.core.acl.AclEntry;
import com.norconex.committer.core.acl.AclKind;

/**
 * Writes a document's standard {@link Acl} as the access control of a Google
 * Cloud Search item. Cloud Search takes one list of readers, one of denied
 * readers and one of owners, each person or group named by the email address
 * of their Google account, or the whole domain. An ACL it cannot express
 * (see {@link #problem(Acl)}) is not approximated, since that could show a
 * document to someone who may not open it.
 */
final class GoogleCloudSearchAcls {

    private GoogleCloudSearchAcls() {
    }

    /**
     * @param acl the document's ACL
     * @return why Cloud Search cannot express it, or {@code null} if it can
     */
    static String problem(Acl acl) {
        if (acl.levels().size() != 1) {
            return "Google Cloud Search takes a single level of permissions "
                    + "and this document has " + acl.levels().size();
        }
        var allowed = false;
        for (var entry : acl.levels().get(0).entries()) {
            var why = entryProblem(entry);
            if (why != null) {
                return why;
            }
            allowed |= entry.access() == AclAccess.ALLOW;
        }
        if (!allowed) {
            return "no one is allowed to read this document";
        }
        for (var owner : acl.owners()) {
            if (account(owner) == null) {
                return "the owner " + owner.principalText()
                        + " has no Google account address";
            }
        }
        return null;
    }

    /**
     * @param acl the document's ACL, for which {@link #problem(Acl)} is
     *            {@code null}
     * @return the Cloud Search access control
     */
    static ItemAcl toItemAcl(Acl acl) {
        var readers = new ArrayList<Principal>();
        var denied = new ArrayList<Principal>();
        for (var entry : acl.levels().get(0).entries()) {
            (entry.access() == AclAccess.DENY ? denied : readers)
                    .add(principal(entry));
        }
        var owners = acl.owners().stream().map(
                GoogleCloudSearchAcls::principal).toList();
        var itemAcl = new ItemAcl().setReaders(readers);
        if (!denied.isEmpty()) {
            itemAcl.setDeniedReaders(denied);
        }
        if (!owners.isEmpty()) {
            itemAcl.setOwners(owners);
        }
        return itemAcl;
    }

    private static String entryProblem(AclEntry entry) {
        if (isDomain(entry)) {
            return entry.access() == AclAccess.DENY
                    ? "Google Cloud Search cannot deny the whole domain"
                    : null;
        }
        return account(entry) == null
                ? entry.principalText() + " has no Google account address"
                : null;
    }

    private static boolean isDomain(AclEntry entry) {
        return entry.kind() == AclKind.SPECIAL
                && List.of(AclEntry.SPECIAL_EVERYONE,
                        AclEntry.SPECIAL_AUTHENTICATED,
                        AclEntry.SPECIAL_DOMAIN).contains(entry.id());
    }

    /** The account address of a user or group, or null if it has none. */
    private static String account(AclEntry entry) {
        return (entry.kind() == AclKind.USER || entry.kind() == AclKind.GROUP)
                && entry.id().contains("@") ? entry.id() : null;
    }

    private static Principal principal(AclEntry entry) {
        var google = new GSuitePrincipal();
        if (isDomain(entry)) {
            google.setGsuiteDomain(true);
        } else if (entry.kind() == AclKind.GROUP) {
            google.setGsuiteGroupEmail(entry.id());
        } else {
            google.setGsuiteUserEmail(entry.id());
        }
        return new Principal().setGsuitePrincipal(google);
    }
}
