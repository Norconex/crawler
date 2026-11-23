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
package com.norconex.crawler.fs.fetch.impl.smb;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.norconex.committer.core.acl.AclAccess;
import com.norconex.committer.core.acl.AclEntry;
import com.norconex.committer.core.acl.AclKind;
import com.norconex.committer.core.acl.AclLevel;

import jcifs.ACE;
import jcifs.SID;

/**
 * Turns SMB access control entries into the standard. An entry allowing or
 * denying a right is an allow or a deny; entries that only apply to what a
 * folder will hold are left out.
 */
final class SmbAcls {

    /** The rights that let a person read a file's content. */
    static final int READ_RIGHTS =
            ACE.FILE_READ_DATA | ACE.GENERIC_READ | ACE.GENERIC_ALL;
    /** The rights that let a person go through a folder. */
    static final int TRAVERSE_RIGHTS =
            ACE.FILE_EXECUTE | ACE.GENERIC_EXECUTE | ACE.GENERIC_ALL;

    private static final String EVERYONE_SID = "S-1-1-0";
    private static final String AUTHENTICATED_SID = "S-1-5-11";
    private static final String BUILTIN_PREFIX = "S-1-5-32-";

    private SmbAcls() {
    }

    /**
     * @param name    the level's name
     * @param aces    the entries
     * @param rights  the rights that count (any of them)
     * @return who has those rights, as a level
     */
    static AclLevel level(String name, ACE[] aces, int rights) {
        List<AclEntry> entries = new ArrayList<>();
        for (ACE ace : aces) {
            if ((ace.getFlags() & ACE.FLAGS_INHERIT_ONLY) != 0
                    || (ace.getAccessMask() & rights) == 0
                    || ace.getSID() == null) {
                continue;
            }
            entries.add(entry(ace.getSID(),
                    ace.isAllow() ? AclAccess.ALLOW : AclAccess.DENY));
        }
        return new AclLevel(name, entries);
    }

    /**
     * The entries as the server gave them, for the {@code native} part of
     * the ACL: allowed or denied, the SID and its name when resolved, the
     * access mask in hexadecimal and the flags.
     * @param aces the entries
     * @return one text per entry
     */
    static List<String> nativeEntries(ACE[] aces) {
        List<String> list = new ArrayList<>();
        for (ACE ace : aces) {
            var sid = ace.getSID();
            list.add((ace.isAllow() ? "ALLOW" : "DENY") + ":"
                    + (sid == null ? "" : sid.toString()) + ":"
                    + (sid == null ? ""
                            : StringUtils.defaultString(
                                    displayName(sid)))
                    + ":0x" + Integer.toHexString(ace.getAccessMask())
                    + ":0x" + Integer.toHexString(ace.getFlags()));
        }
        return list;
    }

    /**
     * One principal, by its SID (stable), with its account name when the
     * server resolved it. Everyone and Authenticated Users are the standard
     * {@code special} principals; the server's own groups ({@code BUILTIN})
     * and well-known groups belong to {@code windows:<domain>}; domain users
     * and groups to {@code ad:<domain>}, the domain being its name when
     * resolved, its SID otherwise.
     */
    static AclEntry entry(SID sid, AclAccess access) {
        var sidText = sid.toString();
        if (EVERYONE_SID.equals(sidText)) {
            return new AclEntry(AclKind.SPECIAL, null,
                    AclEntry.SPECIAL_EVERYONE, null, access);
        }
        if (AUTHENTICATED_SID.equals(sidText)) {
            return new AclEntry(AclKind.SPECIAL, null,
                    AclEntry.SPECIAL_AUTHENTICATED, null, access);
        }
        var type = sid.getType();
        var kind = switch (type) {
            case SID.SID_TYPE_USER -> AclKind.USER;
            case SID.SID_TYPE_DOM_GRP, SID.SID_TYPE_ALIAS,
                    SID.SID_TYPE_WKN_GRP -> AclKind.GROUP;
            default -> AclKind.UNDEFINED;
        };
        var domainName = StringUtils.trimToNull(sid.getDomainName());
        String dir;
        if (type == SID.SID_TYPE_WKN_GRP || type == SID.SID_TYPE_ALIAS
                || sidText.startsWith(BUILTIN_PREFIX)) {
            dir = "windows:" + StringUtils.defaultString(domainName, "BUILTIN");
        } else if (domainName != null) {
            dir = "ad:" + domainName;
        } else {
            dir = "ad:" + domainSidText(sid);
        }
        return new AclEntry(kind, dir, sidText, displayName(sid), access);
    }

    private static String displayName(SID sid) {
        var account = StringUtils.trimToNull(sid.getAccountName());
        if (account == null) {
            return null;
        }
        var domain = StringUtils.trimToNull(sid.getDomainName());
        return domain == null ? account : domain + "\\" + account;
    }

    // The SID without its last part (the account's), which is the domain's.
    private static String domainSidText(SID sid) {
        var text = sid.toString();
        var idx = text.lastIndexOf('-');
        return idx > 0 && text.chars().filter(c -> c == '-').count() > 3
                ? text.substring(0, idx)
                : text;
    }
}
