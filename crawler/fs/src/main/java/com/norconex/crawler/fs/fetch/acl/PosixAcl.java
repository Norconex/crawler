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
package com.norconex.crawler.fs.fetch.acl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.norconex.committer.core.acl.AclAccess;
import com.norconex.committer.core.acl.AclEntry;
import com.norconex.committer.core.acl.AclKind;
import com.norconex.committer.core.acl.AclLevel;

import lombok.NonNull;

/**
 * <p>
 * The permissions of one file or folder of a POSIX-style source (a Unix
 * file system, SFTP, ADLS Gen2, HDFS): its owner, group and mode bits, and
 * any extended entries (named users and groups, and the mask), turned into
 * the standard {@link AclLevel} for one right.
 * </p>
 * <p>
 * POSIX checks the classes in order and stops at the first that matches:
 * the owner, then named users, then the groups (any group the person is in
 * that grants the right), then everyone else. The standard has no order,
 * only "a deny wins", so a class without the right is written as a deny
 * whenever a later class could otherwise let the same person in: an owner
 * who may not read is denied when a group or everyone may. The translation
 * never lets in anyone POSIX keeps out. It can keep out a person POSIX lets
 * in, in one rare case: an owner (or named user) with the right who is also
 * in a group that is denied.
 * </p>
 */
public final class PosixAcl {

    /** The read right (r). */
    public static final int READ = 4;
    /** The write right (w). */
    public static final int WRITE = 2;
    /** The execute right (x), which on a folder is the right to go in. */
    public static final int EXECUTE = 1;

    /**
     * A user or group, by the most stable id the source gives, and a
     * readable name when the source gives one besides.
     * @param id   the id (for example a uid number, an object id, or the
     *             name when nothing else is given)
     * @param name a readable name, or {@code null}
     */
    public record Principal(@NonNull String id, String name) {
        /**
         * @param name a name, used as the id
         * @return a principal known only by its name
         */
        public static Principal named(String name) {
            return new Principal(name, null);
        }

        String text() {
            return name == null ? id : name;
        }
    }

    /**
     * An entry beside the mode bits that allows or denies rights directly,
     * the way macOS ACL entries do (they are checked before the mode bits).
     * @param kind      user or group
     * @param principal who
     * @param allow     allowed, or denied
     * @param rights    the rights concerned, as {@link #READ},
     *                  {@link #WRITE} and {@link #EXECUTE} bits
     * @param everyone  whether the principal stands for everybody (macOS's
     *                  {@code everyone} group)
     */
    public record Direct(
            AclKind kind, Principal principal, boolean allow, int rights,
            boolean everyone) {
    }

    private record Named(Principal principal, int perms) {
    }

    private final String dir;
    private final Principal owner;
    private final Principal group;
    private int ownerPerms;
    private int groupPerms;
    private int otherPerms;
    private Integer mask;
    private final List<Named> users = new ArrayList<>();
    private final List<Named> groups = new ArrayList<>();
    private final List<Direct> directs = new ArrayList<>();
    private final List<String> nativeEntries = new ArrayList<>();

    /**
     * @param dir   the directory users and groups belong to, as
     *              {@code <system>:<name>} (for example
     *              {@code unix:host.example.com})
     * @param owner the owning user, or {@code null} if unknown
     * @param group the owning group, or {@code null} if unknown
     * @param mode  the mode bits ({@code 0640}, for example); only the
     *              lowest nine are used
     */
    public PosixAcl(String dir, Principal owner, Principal group, int mode) {
        this.dir = dir;
        this.owner = owner;
        this.group = group;
        ownerPerms = (mode >> 6) & 7;
        groupPerms = (mode >> 3) & 7;
        otherPerms = mode & 7;
    }

    /**
     * Sets the owner's rights, as given by an extended ACL's own
     * {@code user::} entry.
     * @param perms the rights, as {@link #READ}, {@link #WRITE} and
     *              {@link #EXECUTE} bits
     * @return this
     */
    public PosixAcl ownerPerms(int perms) {
        ownerPerms = perms & 7;
        return this;
    }

    /**
     * Sets the owning group's rights, as given by an extended ACL's own
     * {@code group::} entry (when a file has an extended ACL, the group bits
     * of its mode hold the mask instead).
     * @param perms the rights
     * @return this
     */
    public PosixAcl groupPerms(int perms) {
        groupPerms = perms & 7;
        return this;
    }

    /**
     * Sets everyone else's rights, as given by an extended ACL's
     * {@code other::} entry.
     * @param perms the rights
     * @return this
     */
    public PosixAcl otherPerms(int perms) {
        otherPerms = perms & 7;
        return this;
    }

    /**
     * Sets the mask, which limits the rights of named users, named groups
     * and the owning group.
     * @param perms the rights the mask lets through
     * @return this
     */
    public PosixAcl mask(int perms) {
        mask = perms & 7;
        return this;
    }

    /**
     * Adds a named user entry of an extended ACL.
     * @param user  the user
     * @param perms the rights, before the mask
     * @return this
     */
    public PosixAcl user(Principal user, int perms) {
        users.add(new Named(user, perms & 7));
        return this;
    }

    /**
     * Adds a named group entry of an extended ACL.
     * @param grp   the group
     * @param perms the rights, before the mask
     * @return this
     */
    public PosixAcl group(Principal grp, int perms) {
        groups.add(new Named(grp, perms & 7));
        return this;
    }

    /**
     * Adds an entry that allows or denies rights directly (macOS).
     * @param direct the entry
     * @return this
     */
    public PosixAcl direct(Direct direct) {
        directs.add(direct);
        return this;
    }

    /**
     * Keeps one of the source's own entries, as it gave it, for the
     * {@code native} part of the ACL.
     * @param entry the entry, as text
     * @return this
     */
    public PosixAcl nativeEntry(String entry) {
        nativeEntries.add(entry);
        return this;
    }

    /**
     * @return whether named users or groups were added
     */
    public boolean hasExtendedEntries() {
        return !users.isEmpty() || !groups.isEmpty() || !directs.isEmpty();
    }

    /**
     * Who has one right, as a level.
     * @param levelName the level's name
     * @param right     {@link #READ} or {@link #EXECUTE}, typically
     * @return the level
     */
    public AclLevel level(String levelName, int right) {
        var effectiveMask = mask == null ? 7 : mask;
        var othersHave = has(otherPerms, right);
        var groupsGrant = has(groupPerms & effectiveMask, right)
                || groups.stream().anyMatch(
                        g -> has(g.perms & effectiveMask, right));
        var userDenyNeeded = groupsGrant || othersHave;

        List<AclEntry> entries = new ArrayList<>();
        if (owner != null) {
            if (has(ownerPerms, right)) {
                entries.add(entry(AclKind.USER, owner, AclAccess.ALLOW));
            } else if (userDenyNeeded) {
                entries.add(entry(AclKind.USER, owner, AclAccess.DENY));
            }
        }
        for (Named u : users) {
            if (has(u.perms & effectiveMask, right)) {
                entries.add(entry(AclKind.USER, u.principal, AclAccess.ALLOW));
            } else if (userDenyNeeded) {
                entries.add(entry(AclKind.USER, u.principal, AclAccess.DENY));
            }
        }
        if (group != null) {
            if (has(groupPerms & effectiveMask, right)) {
                entries.add(entry(AclKind.GROUP, group, AclAccess.ALLOW));
            } else if (othersHave) {
                entries.add(entry(AclKind.GROUP, group, AclAccess.DENY));
            }
        }
        for (Named g : groups) {
            if (has(g.perms & effectiveMask, right)) {
                entries.add(
                        entry(AclKind.GROUP, g.principal, AclAccess.ALLOW));
            } else if (othersHave) {
                entries.add(
                        entry(AclKind.GROUP, g.principal, AclAccess.DENY));
            }
        }
        if (othersHave) {
            entries.add(AclEntry.allowSpecial(AclEntry.SPECIAL_EVERYONE));
        }
        for (Direct d : directs) {
            if (!has(d.rights, right)) {
                continue;
            }
            var access = d.allow ? AclAccess.ALLOW : AclAccess.DENY;
            entries.add(d.everyone
                    ? new AclEntry(AclKind.SPECIAL, null,
                            AclEntry.SPECIAL_EVERYONE, null, access)
                    : entry(d.kind, d.principal, access));
        }
        return new AclLevel(levelName, entries);
    }

    /**
     * @return the owner, as the ACL's owners
     */
    public List<AclEntry> owners() {
        return owner == null
                ? List.of()
                : List.of(entry(AclKind.USER, owner, null));
    }

    /**
     * The source's own entries, in the {@code getfacl} way of writing them
     * when none were kept as given.
     * @return the native part of the ACL
     */
    public Map<String, Object> nativeData() {
        Map<String, Object> data = new LinkedHashMap<>();
        if (owner != null) {
            data.put("owner", owner.text());
        }
        if (group != null) {
            data.put("group", group.text());
        }
        List<String> lines = new ArrayList<>();
        lines.add("user::" + permText(ownerPerms));
        users.forEach(u -> lines.add(
                "user:" + u.principal.text() + ":" + permText(u.perms)));
        lines.add("group::" + permText(groupPerms));
        groups.forEach(g -> lines.add(
                "group:" + g.principal.text() + ":" + permText(g.perms)));
        if (mask != null) {
            lines.add("mask::" + permText(mask));
        }
        lines.add("other::" + permText(otherPerms));
        data.put("entries", lines);
        if (!nativeEntries.isEmpty()) {
            data.put("extended", List.copyOf(nativeEntries));
        }
        return data;
    }

    /**
     * Whether a level lets everybody in: it allows everyone and denies
     * nobody. Such a level can be left out, as it changes nothing.
     * @param level the level
     * @return {@code true} if it lets everybody in
     */
    public static boolean admitsEverybody(AclLevel level) {
        var entries = level.entries();
        return entries.stream().noneMatch(e -> e.access() == AclAccess.DENY)
                && entries.stream().anyMatch(e -> e.kind() == AclKind.SPECIAL
                        && AclEntry.SPECIAL_EVERYONE.equals(e.id()));
    }

    /**
     * Reads rights written the {@code rwx} way ({@code r-x}, for example),
     * any other letter than {@code r}, {@code w} and {@code x} ignored.
     * @param text the rights as text
     * @return the rights, as {@link #READ}, {@link #WRITE} and
     *         {@link #EXECUTE} bits
     */
    public static int parsePerms(String text) {
        var perms = 0;
        if (text == null) {
            return perms;
        }
        for (char c : text.toCharArray()) {
            perms |= switch (c) {
                case 'r' -> READ;
                case 'w' -> WRITE;
                case 'x', 's', 't' -> EXECUTE;
                default -> 0;
            };
        }
        return perms;
    }

    /**
     * Reads a mode written in octal ({@code 755}, {@code 1777}), or the
     * {@code rwxr-x---} way.
     * @param text the mode
     * @return the mode bits
     * @throws IllegalArgumentException if the mode is not readable
     */
    public static int parseMode(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("No mode.");
        }
        var t = text.trim();
        if (t.matches("[0-7]{3,4}")) {
            return Integer.parseInt(t, 8) & 0777;
        }
        if (t.length() >= 9) {
            return parsePerms(t.substring(0, 3)) << 6
                    | parsePerms(t.substring(3, 6)) << 3
                    | parsePerms(t.substring(6, 9));
        }
        throw new IllegalArgumentException("Not a mode: " + text);
    }

    /**
     * The mode bits of a set of Java POSIX permissions.
     * @param perms the permissions
     * @return the mode bits
     */
    public static int mode(
            Set<java.nio.file.attribute.PosixFilePermission> perms) {
        var mode = 0;
        for (var p : perms) {
            mode |= switch (p) {
                case OWNER_READ -> 0400;
                case OWNER_WRITE -> 0200;
                case OWNER_EXECUTE -> 0100;
                case GROUP_READ -> 040;
                case GROUP_WRITE -> 020;
                case GROUP_EXECUTE -> 010;
                case OTHERS_READ -> 04;
                case OTHERS_WRITE -> 02;
                case OTHERS_EXECUTE -> 01;
            };
        }
        return mode;
    }

    private AclEntry entry(AclKind kind, Principal p, AclAccess access) {
        return new AclEntry(kind, dir, p.id, p.name, access);
    }

    private static boolean has(int perms, int right) {
        return (perms & right) != 0;
    }

    private static String permText(int perms) {
        return (has(perms, READ) ? "r" : "-")
                + (has(perms, WRITE) ? "w" : "-")
                + (has(perms, EXECUTE) ? "x" : "-");
    }
}
