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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.committer.core.acl.AclAccess;
import com.norconex.committer.core.acl.AclEntry;
import com.norconex.committer.core.acl.AclKind;
import com.norconex.crawler.fs.fetch.acl.PosixAcl.Principal;

class NativePosixAclsTest {

    private static final String DIR = "unix:host";

    @TempDir
    private Path tempDir;

    private static byte[] xattr(int[][] entries) {
        var bb = ByteBuffer.allocate(4 + entries.length * 8)
                .order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(2);
        for (int[] e : entries) {
            bb.putShort((short) e[0]).putShort((short) e[1]).putInt(e[2]);
        }
        return bb.array();
    }

    @Test
    void testLinuxXattrFormat() throws IOException {
        // user::rw-, user:1001:r--, group::---, group:200:r--, mask::r--,
        // other::---
        var data = xattr(new int[][] {
                { 0x01, 6, -1 },
                { 0x02, 4, 1001 },
                { 0x04, 0, -1 },
                { 0x08, 4, 200 },
                { 0x10, 4, -1 },
                { 0x20, 0, -1 } });
        var acl = new PosixAcl(DIR, new Principal("1000", "pascal"),
                new Principal("100", "staff"), 0640);

        NativePosixAcls.applyLinuxXattr(data, data.length, acl,
                uid -> uid == 1001 ? "jane" : null, gid -> null);

        assertThat(acl.level("file", PosixAcl.READ).entries())
                .containsExactlyInAnyOrder(
                        new AclEntry(AclKind.USER, DIR, "1000", "pascal",
                                AclAccess.ALLOW),
                        new AclEntry(AclKind.USER, DIR, "1001", "jane",
                                AclAccess.ALLOW),
                        new AclEntry(AclKind.GROUP, DIR, "200", null,
                                AclAccess.ALLOW));
    }

    @Test
    void testLinuxXattrBadFormat() {
        var acl = new PosixAcl(DIR, null, null, 0);
        assertThatThrownBy(() -> NativePosixAcls.applyLinuxXattr(
                new byte[] { 2, 0, 0, 0, 1 }, 5, acl, i -> null, i -> null))
                        .isInstanceOf(IOException.class);
        var v1 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(1).array();
        assertThatThrownBy(() -> NativePosixAcls.applyLinuxXattr(
                v1, 4, acl, i -> null, i -> null))
                        .hasMessageContaining("version 1");
        var badTag = xattr(new int[][] { { 0x40, 4, 1 } });
        assertThatThrownBy(() -> NativePosixAcls.applyLinuxXattr(
                badTag, badTag.length, acl, i -> null, i -> null))
                        .hasMessageContaining("tag");
    }

    @Test
    void testMacText() throws IOException {
        var text = """
            !#acl 1
            user:FFFFEEEE-DDDD-CCCC-BBBB-AAAA000001F5:jo:501:deny:read
            group:ABCDEFAB-CDEF-ABCD-EFAB-CDEF0000000C:everyone:12:allow:read,write
            group:ABCDEFAB-CDEF-ABCD-EFAB-CDEF00000050:admin:80:allow,only_inherit:read
            user:FFFFEEEE-DDDD-CCCC-BBBB-AAAA000001F6:ann:502:allow,file_inherit:read,execute
            """;
        var acl = new PosixAcl(DIR, new Principal("1000", "pascal"),
                new Principal("20", "staff"), 0600);

        assertThat(NativePosixAcls.applyMacText(text, acl)).isTrue();

        assertThat(acl.level("file", PosixAcl.READ).entries())
                .containsExactlyInAnyOrder(
                        new AclEntry(AclKind.USER, DIR, "1000", "pascal",
                                AclAccess.ALLOW),
                        new AclEntry(AclKind.USER, DIR, "501", "jo",
                                AclAccess.DENY),
                        new AclEntry(AclKind.SPECIAL, null,
                                AclEntry.SPECIAL_EVERYONE, null,
                                AclAccess.ALLOW),
                        new AclEntry(AclKind.USER, DIR, "502", "ann",
                                AclAccess.ALLOW));
        assertThat(acl.level("path", PosixAcl.EXECUTE).entries())
                .contains(new AclEntry(AclKind.USER, DIR, "502", "ann",
                        AclAccess.ALLOW));
        assertThat(acl.nativeData().get("extended").toString())
                .contains("only_inherit");
        assertThat(NativePosixAcls.applyMacText("!#acl 1\n",
                new PosixAcl(DIR, null, null, 0))).isFalse();
        assertThatThrownBy(() -> NativePosixAcls.applyMacText(
                "user:x:y:allow:read", acl)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> NativePosixAcls.applyMacText(
                "role:u:n:1:allow:read", acl)).isInstanceOf(IOException.class);
    }

    @Test
    void testUnavailableOutsideLinuxAndMac() {
        var reason = NativePosixAcls.unavailableReason();
        if (OS.LINUX.isCurrentOs() || OS.MAC.isCurrentOs()) {
            assertThat(reason).isNull();
        } else {
            assertThat(reason).contains("only be read on Linux and macOS");
            assertThatThrownBy(() -> NativePosixAcls.read(tempDir,
                    new PosixAcl(DIR, null, null, 0)))
                            .isInstanceOf(IOException.class);
        }
    }

    // Sets a real extended ACL with setfacl and reads it back through JNA.
    @Test
    @EnabledOnOs(OS.LINUX)
    void testReadsRealLinuxAcl() throws Exception {
        var file = Files.writeString(tempDir.resolve("acl.txt"), "x");
        assumeTrue(run("setfacl", "-m", "u:0:r--,g:0:---,m::r--",
                file.toString()) == 0,
                "setfacl is not available or the file system has no ACLs");
        var attrs = Files.readAttributes(file, PosixFileAttributes.class);
        var acl = new PosixAcl(DIR, Principal.named(attrs.owner().getName()),
                Principal.named(attrs.group().getName()),
                PosixAcl.mode(attrs.permissions()));

        assertThat(NativePosixAcls.read(file, acl)).isTrue();

        assertThat(acl.level("file", PosixAcl.READ).entries())
                .anySatisfy(e -> {
                    assertThat(e.kind()).isEqualTo(AclKind.USER);
                    assertThat(e.id()).isEqualTo("0");
                    assertThat(e.name()).isEqualTo("root");
                    assertThat(e.access()).isEqualTo(AclAccess.ALLOW);
                });
        // a file without an extended ACL has none
        var plain = Files.writeString(tempDir.resolve("plain.txt"), "x");
        assertThat(NativePosixAcls.read(plain,
                new PosixAcl(DIR, null, null, 0644))).isFalse();
    }

    private static int run(String... command) {
        try {
            var p = new ProcessBuilder(command).redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) ? p.exitValue() : -1;
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
