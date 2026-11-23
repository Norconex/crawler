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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;

import com.norconex.committer.core.acl.AclKind;
import com.norconex.crawler.fs.fetch.acl.PosixAcl.Direct;
import com.norconex.crawler.fs.fetch.acl.PosixAcl.Principal;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;

/**
 * <p>
 * Reads the extended ACLs of local files on Linux and macOS, which Java
 * itself cannot read: Java's {@code posix} view gives only the mode bits.
 * The operating system's C library is called through JNA, which finds it
 * when first used; nothing is compiled for an operating system, and no
 * tool needs to be installed on the host.
 * </p>
 * <ul>
 *   <li><b>Linux</b>: the {@code system.posix_acl_access} extended
 *       attribute (what {@code setfacl} writes), read with
 *       {@code getxattr}. A file without it has no extended ACL.</li>
 *   <li><b>macOS</b>: the file's ACL, read with {@code acl_get_file} and
 *       {@code acl_to_text}.</li>
 * </ul>
 */
public final class NativePosixAcls {

    // Linux errno values
    private static final int ENODATA = 61;
    private static final int ERANGE = 34;
    private static final int EOPNOTSUPP = 95;
    // macOS errno values
    private static final int MAC_ENOENT = 2;
    private static final int MAC_ENOTSUP = 45;
    private static final int MAC_ACL_TYPE_EXTENDED = 0x00000100;

    private static final String LINUX_ACL_ATTRIBUTE =
            "system.posix_acl_access";
    private static final int LINUX_ACL_VERSION = 2;
    private static final int TAG_USER_OBJ = 0x01;
    private static final int TAG_USER = 0x02;
    private static final int TAG_GROUP_OBJ = 0x04;
    private static final int TAG_GROUP = 0x08;
    private static final int TAG_MASK = 0x10;
    private static final int TAG_OTHER = 0x20;

    private static final Map<Integer, String> USER_NAMES =
            new ConcurrentHashMap<>();
    private static final Map<Integer, String> GROUP_NAMES =
            new ConcurrentHashMap<>();

    private NativePosixAcls() {
    }

    /**
     * Why extended ACLs cannot be read on this system, or {@code null} if
     * they can.
     * @return the reason, or {@code null}
     */
    public static String unavailableReason() {
        return Holder.UNAVAILABLE_REASON;
    }

    /**
     * Adds a local file's extended ACL entries to its permissions.
     * @param path the file
     * @param acl  its permissions, from its mode bits
     * @return whether the file has an extended ACL
     * @throws IOException if extended ACLs cannot be read here, or the
     *     operating system could not read this file's
     */
    public static boolean read(Path path, PosixAcl acl) throws IOException {
        if (unavailableReason() != null) {
            throw new IOException(unavailableReason());
        }
        var file = path.toAbsolutePath().toString();
        return SystemUtils.IS_OS_MAC
                ? readMac(file, acl)
                : readLinux(file, acl);
    }

    //--- Linux ----------------------------------------------------------------

    private static boolean readLinux(String file, PosixAcl acl)
            throws IOException {
        var lib = Holder.linux();
        var buf = new byte[512];
        var size = lib.getxattr(
                file, LINUX_ACL_ATTRIBUTE, buf, new NativeLong(buf.length))
                .longValue();
        if (size < 0) {
            var errno = Native.getLastError();
            if (errno == ENODATA || errno == EOPNOTSUPP) {
                return false;
            }
            if (errno != ERANGE) {
                throw new IOException("Could not read the ACL of " + file
                        + " (getxattr error " + errno + ").");
            }
            size = lib.getxattr(file, LINUX_ACL_ATTRIBUTE, null,
                    new NativeLong(0)).longValue();
            buf = new byte[(int) Math.max(size, 0)];
            size = lib.getxattr(file, LINUX_ACL_ATTRIBUTE, buf,
                    new NativeLong(buf.length)).longValue();
            if (size < 0) {
                throw new IOException("Could not read the ACL of " + file
                        + " (getxattr error " + Native.getLastError() + ").");
            }
        }
        applyLinuxXattr(buf, (int) size, acl,
                NativePosixAcls::userName, NativePosixAcls::groupName);
        return true;
    }

    /**
     * Adds the entries of a Linux {@code system.posix_acl_access} value: a
     * version (2), then entries of a tag, rights and a numeric id, all
     * little-endian.
     * @param data      the value
     * @param length    how many bytes of it are used
     * @param acl       the permissions to add to
     * @param userName  gives a user's name from its id, or {@code null}
     * @param groupName gives a group's name from its id, or {@code null}
     * @throws IOException if the value is not in that format
     */
    static void applyLinuxXattr(
            byte[] data, int length, PosixAcl acl,
            IntFunction<String> userName, IntFunction<String> groupName)
            throws IOException {
        var bb = ByteBuffer.wrap(data, 0, length)
                .order(ByteOrder.LITTLE_ENDIAN);
        if (length < 4 || (length - 4) % 8 != 0) {
            throw new IOException("Not a POSIX ACL value: " + length
                    + " bytes.");
        }
        var version = bb.getInt();
        if (version != LINUX_ACL_VERSION) {
            throw new IOException("POSIX ACL of version " + version
                    + ", expected " + LINUX_ACL_VERSION + ".");
        }
        while (bb.remaining() >= 8) {
            var tag = Short.toUnsignedInt(bb.getShort());
            var perms = Short.toUnsignedInt(bb.getShort()) & 7;
            var id = bb.getInt();
            switch (tag) {
                case TAG_USER_OBJ -> acl.ownerPerms(perms);
                case TAG_GROUP_OBJ -> acl.groupPerms(perms);
                case TAG_OTHER -> acl.otherPerms(perms);
                case TAG_MASK -> acl.mask(perms);
                case TAG_USER -> acl.user(principal(id, userName), perms);
                case TAG_GROUP -> acl.group(principal(id, groupName), perms);
                default -> throw new IOException(
                        "Unknown POSIX ACL entry tag: " + tag);
            }
        }
    }

    private static Principal principal(int id, IntFunction<String> names) {
        var idText = Integer.toUnsignedString(id);
        return new Principal(idText, names.apply(id));
    }

    private static String userName(int uid) {
        return USER_NAMES.computeIfAbsent(uid, id -> {
            var lib = Holder.linux();
            synchronized (NativePosixAcls.class) {
                return firstString(lib.getpwuid(id));
            }
        });
    }

    private static String groupName(int gid) {
        return GROUP_NAMES.computeIfAbsent(gid, id -> {
            var lib = Holder.linux();
            synchronized (NativePosixAcls.class) {
                return firstString(lib.getgrgid(id));
            }
        });
    }

    // struct passwd and struct group both start with the name.
    private static String firstString(Pointer struct) {
        if (struct == null) {
            return null;
        }
        var name = struct.getPointer(0);
        return name == null ? null : name.getString(0);
    }

    //--- macOS ----------------------------------------------------------------

    private static boolean readMac(String file, PosixAcl acl)
            throws IOException {
        var lib = Holder.mac();
        var aclPtr = lib.acl_get_file(file, MAC_ACL_TYPE_EXTENDED);
        if (aclPtr == null) {
            var errno = Native.getLastError();
            if (errno == MAC_ENOENT || errno == MAC_ENOTSUP) {
                return false;
            }
            throw new IOException("Could not read the ACL of " + file
                    + " (acl_get_file error " + errno + ").");
        }
        try {
            var textPtr = lib.acl_to_text(aclPtr, null);
            if (textPtr == null) {
                throw new IOException("Could not read the ACL of " + file
                        + " (acl_to_text error " + Native.getLastError()
                        + ").");
            }
            try {
                return applyMacText(textPtr.getString(0), acl);
            } finally {
                lib.acl_free(textPtr);
            }
        } finally {
            lib.acl_free(aclPtr);
        }
    }

    /**
     * Adds the entries of a macOS ACL as {@code acl_to_text} writes it: a
     * header line ({@code !#acl 1}), then one line per entry,
     * {@code user|group:<uuid>:<name>:<id>:allow|deny[,<flags>]:<rights>}.
     * Entries that apply only to what a folder will hold
     * ({@code only_inherit}) are skipped.
     * @param text the ACL as text
     * @param acl  the permissions to add to
     * @return whether there was at least one entry
     * @throws IOException if an entry is not in that format
     */
    static boolean applyMacText(String text, PosixAcl acl)
            throws IOException {
        var any = false;
        for (var rawLine : StringUtils.split(
                StringUtils.defaultString(text), '\n')) {
            var line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("!#acl")) {
                continue;
            }
            var f = line.split(":", -1);
            if (f.length < 6) {
                throw new IOException("Not a macOS ACL entry: " + line);
            }
            var kind = switch (f[0]) {
                case "user" -> AclKind.USER;
                case "group" -> AclKind.GROUP;
                default -> throw new IOException(
                        "Unknown macOS ACL entry kind: " + line);
            };
            var name = StringUtils.trimToNull(
                    String.join(":", Arrays.copyOfRange(
                            f, 2, f.length - 3)));
            var id = StringUtils.trimToNull(f[f.length - 3]);
            var words = new HashSet<String>();
            for (var w : (f[f.length - 2] + "," + f[f.length - 1])
                    .split(",")) {
                words.add(w.trim());
            }
            acl.nativeEntry(line);
            any = true;
            if (words.contains("only_inherit")) {
                continue;
            }
            var allow = words.contains("allow");
            if (!allow && !words.contains("deny")) {
                throw new IOException(
                        "macOS ACL entry neither allows nor denies: " + line);
            }
            var rights = 0;
            if (words.contains("read") || words.contains("list")) {
                rights |= PosixAcl.READ;
            }
            if (words.contains("write") || words.contains("add_file")) {
                rights |= PosixAcl.WRITE;
            }
            if (words.contains("execute") || words.contains("search")) {
                rights |= PosixAcl.EXECUTE;
            }
            var everyone = kind == AclKind.GROUP
                    && ("everyone".equals(name) || "12".equals(id));
            var principal = id != null
                    ? new Principal(id, name)
                    : Principal.named(StringUtils.defaultIfBlank(
                            name, f[1]));
            acl.direct(new Direct(kind, principal, allow, rights, everyone));
        }
        return any;
    }

    //--- JNA ------------------------------------------------------------------

    /** The Linux C library functions used. */
    interface LinuxC extends Library {
        NativeLong getxattr(
                String path, String name, byte[] value, NativeLong size);

        Pointer getpwuid(int uid);

        Pointer getgrgid(int gid);
    }

    /** The macOS C library functions used. */
    interface MacC extends Library {
        Pointer acl_get_file(String path, int type);

        Pointer acl_to_text(Pointer acl, Pointer lenP);

        int acl_free(Pointer obj);
    }

    // Loaded on first use only, so a system without JNA support fails here,
    // once, rather than when this class is loaded.
    private static final class Holder {
        private static final Object LIB;
        private static final String UNAVAILABLE_REASON;

        static {
            Object lib = null;
            String reason = null;
            try {
                if (SystemUtils.IS_OS_LINUX) {
                    lib = Native.load("c", LinuxC.class);
                } else if (SystemUtils.IS_OS_MAC) {
                    lib = Native.load("c", MacC.class);
                } else {
                    reason = "Extended ACLs can only be read on Linux and "
                            + "macOS, not on " + SystemUtils.OS_NAME + ".";
                }
            } catch (LinkageError | RuntimeException e) {
                reason = "The system's C library could not be loaded to "
                        + "read extended ACLs (" + e + ").";
            }
            LIB = lib;
            UNAVAILABLE_REASON = reason;
        }

        static LinuxC linux() {
            return (LinuxC) LIB;
        }

        static MacC mac() {
            return (MacC) LIB;
        }
    }
}
