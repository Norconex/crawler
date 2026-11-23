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
package com.norconex.crawler.fs.fetch.impl.local;

import java.io.IOException;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.norconex.committer.core.acl.AclAccess;
import com.norconex.committer.core.acl.AclEntry;
import com.norconex.committer.core.acl.AclKind;
import com.norconex.committer.core.acl.AclLevel;

/**
 * Turns the ACL Java reads for a local file through its {@code acl} view
 * (Windows, mostly) into the standard: an entry allowing or denying the
 * read-data right is an allow or a deny. Inherited entries are already part
 * of what Java gives. Entries that only apply to what a folder will hold
 * ({@code INHERIT_ONLY}), and audit and alarm entries, are left out.
 */
final class WindowsAcls {

    /** Domains that are the local computer's own, not a directory's. */
    private static final Set<String> LOCAL_DOMAINS = Set.of(
            "BUILTIN", "NT AUTHORITY", "NT SERVICE", "NT VIRTUAL MACHINE",
            "APPLICATION PACKAGE AUTHORITY", "IIS APPPOOL", "WINDOW MANAGER",
            "FONT DRIVER HOST");

    private WindowsAcls() {
    }

    /** What is read, for a fetcher to write. */
    record Read(
            AclLevel level, List<AclEntry> owners,
            Map<String, Object> nativeData) {
    }

    static Read read(AclFileAttributeView view, String computer)
            throws IOException {
        List<AclEntry> entries = new ArrayList<>();
        List<String> nativeEntries = new ArrayList<>();
        for (var ace : view.getAcl()) {
            var principal = ace.principal();
            nativeEntries.add(ace.type() + ":"
                    + (principal == null ? "" : principal.getName()) + ":"
                    + String.join("/", new TreeSet<>(
                            ace.permissions().stream().map(Enum::name)
                                    .toList()))
                    + (ace.flags().isEmpty() ? ""
                            : ":" + String.join("/",
                                    new TreeSet<>(ace.flags().stream()
                                            .map(Enum::name).toList()))));
            if (principal == null
                    || ace.flags().contains(AclEntryFlag.INHERIT_ONLY)
                    || !ace.permissions().contains(
                            AclEntryPermission.READ_DATA)) {
                continue;
            }
            AclAccess access;
            if (ace.type() == AclEntryType.ALLOW) {
                access = AclAccess.ALLOW;
            } else if (ace.type() == AclEntryType.DENY) {
                access = AclAccess.DENY;
            } else {
                continue;
            }
            entries.add(entry(principal, computer, access));
        }
        List<AclEntry> owners = new ArrayList<>();
        var owner = view.getOwner();
        if (owner != null) {
            var o = entry(owner, computer, null);
            if (o.kind() != AclKind.SPECIAL) {
                owners.add(o);
            }
        }
        Map<String, Object> nativeData = new LinkedHashMap<>();
        if (owner != null) {
            nativeData.put("owner", owner.getName());
        }
        nativeData.put("entries", nativeEntries);
        return new Read(new AclLevel("file", entries), owners, nativeData);
    }

    /**
     * One principal, from the name Java gives it: {@code DOMAIN\account},
     * a well-known name such as {@code Everyone}, or a SID when Windows
     * could not name it. A domain that is the computer's own (its name,
     * {@code BUILTIN}, {@code NT AUTHORITY}...) gives the directory
     * {@code windows:<domain>}; any other, an Active Directory domain, gives
     * {@code ad:<domain>}.
     */
    static AclEntry entry(
            UserPrincipal principal, String computer, AclAccess access) {
        var name = principal.getName();
        if (Strings.CI.equals(name, "Everyone")) {
            return new AclEntry(AclKind.SPECIAL, null,
                    AclEntry.SPECIAL_EVERYONE, null, access);
        }
        if (Strings.CI.equals(name, "NT AUTHORITY\\Authenticated Users")) {
            return new AclEntry(AclKind.SPECIAL, null,
                    AclEntry.SPECIAL_AUTHENTICATED, null, access);
        }
        var kind = principal instanceof GroupPrincipal
                ? AclKind.GROUP
                : AclKind.USER;
        var domain = StringUtils.substringBefore(name, "\\");
        var account = StringUtils.substringAfter(name, "\\");
        if (!name.contains("\\")) {
            if (name.startsWith("S-1-")) {
                // a SID Windows could not name: no telling what it is
                kind = AclKind.UNDEFINED;
            }
            return new AclEntry(kind, "windows:" + computer, name, null,
                    access);
        }
        var local = LOCAL_DOMAINS.contains(domain.toUpperCase())
                || Strings.CI.equals(domain, computer);
        return new AclEntry(kind, (local ? "windows:" : "ad:") + domain,
                account, null, access);
    }
}
