/* Copyright 2023-2026 Norconex Inc.
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.committer.core.acl.AclCodec;
import com.norconex.committer.core.acl.AclEntry;
import com.norconex.committer.core.acl.AclKind;
import com.norconex.committer.core.acl.AclLevel;
import com.norconex.committer.core.acl.AclScope;
import com.norconex.committer.core.acl.AclStatus;
import com.norconex.commons.lang.map.Properties;
import com.norconex.crawler.core.junit.WithLogLevel;
import com.norconex.crawler.fs.fetch.acl.AclFetchMode;

@Timeout(30)
class LocalFetcherAclTest {

    @TempDir
    private Path tempDir;

    private static UserPrincipal user(String name) {
        return () -> name;
    }

    private static GroupPrincipal group(String name) {
        return () -> name;
    }

    private static java.nio.file.attribute.AclEntry ace(
            AclEntryType type, UserPrincipal p, AclEntryFlag[] flags,
            AclEntryPermission... perms) {
        return java.nio.file.attribute.AclEntry.newBuilder()
                .setType(type)
                .setPrincipal(p)
                .setPermissions(perms)
                .setFlags(flags)
                .build();
    }

    @Test
    void testWindowsAcl() throws IOException {
        var view = mock(AclFileAttributeView.class);
        when(view.getOwner()).thenReturn(user("CORP\\pascal"));
        var none = new AclEntryFlag[0];
        when(view.getAcl()).thenReturn(List.of(
                ace(AclEntryType.ALLOW, group("CORP\\Sales"), none,
                        AclEntryPermission.READ_DATA,
                        AclEntryPermission.READ_ACL),
                ace(AclEntryType.DENY, group("CORP\\Interns"), none,
                        AclEntryPermission.READ_DATA),
                ace(AclEntryType.ALLOW, group("BUILTIN\\Administrators"),
                        new AclEntryFlag[] { AclEntryFlag.INHERIT_ONLY },
                        AclEntryPermission.READ_DATA),
                ace(AclEntryType.ALLOW, group("MYPC\\Helpers"), none,
                        AclEntryPermission.READ_DATA),
                ace(AclEntryType.ALLOW, user("CORP\\jack"), none,
                        AclEntryPermission.WRITE_DATA),
                ace(AclEntryType.ALLOW, group("Everyone"), none,
                        AclEntryPermission.READ_DATA),
                ace(AclEntryType.ALLOW,
                        group("NT AUTHORITY\\Authenticated Users"), none,
                        AclEntryPermission.READ_DATA),
                ace(AclEntryType.AUDIT, user("CORP\\auditor"), none,
                        AclEntryPermission.READ_DATA),
                ace(AclEntryType.ALLOW, user("S-1-5-21-1-2-3-500"), none,
                        AclEntryPermission.READ_DATA)));

        var read = WindowsAcls.read(view, "MYPC");

        assertThat(read.level().name()).isEqualTo("file");
        assertThat(read.level().entries()).containsExactlyInAnyOrder(
                AclEntry.allow(AclKind.GROUP, "ad:CORP", "Sales"),
                AclEntry.deny(AclKind.GROUP, "ad:CORP", "Interns"),
                AclEntry.allow(AclKind.GROUP, "windows:MYPC", "Helpers"),
                AclEntry.allowSpecial(AclEntry.SPECIAL_EVERYONE),
                AclEntry.allowSpecial(AclEntry.SPECIAL_AUTHENTICATED),
                AclEntry.allow(AclKind.UNDEFINED, "windows:MYPC",
                        "S-1-5-21-1-2-3-500"));
        assertThat(read.owners()).containsExactly(
                AclEntry.owner(AclKind.USER, "ad:CORP", "pascal"));
        assertThat(read.nativeData().get("entries").toString())
                .contains("DENY:CORP\\Interns:READ_DATA")
                .contains("AUDIT:CORP\\auditor:READ_DATA");
    }

    @Test
    @WithLogLevel(value = "OFF", classes = LocalFetcher.class)
    void testNoPermissionsGivenIsNone() {
        var fetcher = new LocalFetcher();
        fetcher.getConfiguration().setAcl(AclFetchMode.BASIC);
        var meta = new Properties();
        var attrs = mock(BasicFileAttributes.class);
        // a path whose file system has no acl view and no posix attributes
        var path = mock(Path.class);
        var fileSystem = mock(java.nio.file.FileSystem.class);
        when(path.getFileSystem()).thenReturn(fileSystem);
        when(fileSystem.provider()).thenReturn(
                mock(java.nio.file.spi.FileSystemProvider.class));

        fetcher.fetchAcl(path, attrs, meta);

        var acl = AclCodec.read(meta).orElseThrow();
        assertThat(acl.status()).isEqualTo(AclStatus.NONE);
        assertThat(acl.scope()).isEqualTo(AclScope.BASIC);
        assertThat(acl.unread())
                .containsExactly("the file system gives no permissions");
    }

    @Test
    @EnabledOnOs({ OS.LINUX, OS.MAC })
    void testPosixModeBitsAndPathLevel() throws IOException {
        var dir = Files.createDirectory(tempDir.resolve("private"));
        var file = Files.writeString(dir.resolve("doc.txt"), "x");
        Files.setPosixFilePermissions(file,
                PosixFilePermissions.fromString("rw-r--r--"));
        Files.setPosixFilePermissions(dir,
                PosixFilePermissions.fromString("rwx------"));
        var fetcher = new LocalFetcher();
        fetcher.getConfiguration().setAcl(AclFetchMode.BASIC);
        var meta = new Properties();

        fetcher.fetchAcl(file,
                Files.readAttributes(file, PosixFileAttributes.class), meta);

        var acl = AclCodec.read(meta).orElseThrow();
        assertThat(acl.status()).isEqualTo(AclStatus.COMPLETE);
        assertThat(acl.scope()).isEqualTo(AclScope.BASIC);
        // the file is readable by everyone, but its folder lets only its
        // owner through
        assertThat(acl.levels()).extracting(AclLevel::name)
                .endsWith("path", "file");
        var pathLevel = acl.levels().get(acl.levels().size() - 2);
        assertThat(pathLevel.entries()).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(AclKind.USER);
            assertThat(e.dir()).startsWith("unix:");
        });
        assertThat(acl.levels().getLast().entries())
                .contains(AclEntry.allowSpecial(AclEntry.SPECIAL_EVERYONE));
        assertThat(acl.owners()).singleElement()
                .satisfies(o -> assertThat(o.id()).matches("\\d+"));
        Files.setPosixFilePermissions(dir,
                PosixFilePermissions.fromString("rwx------"));
    }
}
