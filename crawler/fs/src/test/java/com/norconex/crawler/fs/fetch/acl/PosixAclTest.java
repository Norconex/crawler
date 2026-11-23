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

import static com.norconex.committer.core.acl.AclEntry.allow;
import static com.norconex.committer.core.acl.AclEntry.allowSpecial;
import static com.norconex.committer.core.acl.AclEntry.deny;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.norconex.committer.core.acl.AclAccess;
import com.norconex.committer.core.acl.AclEntry;
import com.norconex.committer.core.acl.AclKind;
import com.norconex.committer.core.acl.AclLevel;
import com.norconex.crawler.fs.fetch.acl.PosixAcl.Direct;
import com.norconex.crawler.fs.fetch.acl.PosixAcl.Principal;

class PosixAclTest {

    private static final String DIR = "unix:host";
    private static final Principal OWNER = new Principal("1000", "pascal");
    private static final Principal GROUP = new Principal("100", "staff");

    private static List<AclEntry> read(PosixAcl acl) {
        return acl.level("file", PosixAcl.READ).entries();
    }

    private static AclEntry named(AclEntry e, String name) {
        return e.withName(name);
    }

    @Test
    void testOwnerOnly() {
        // 0600: nobody else, so no deny is needed
        assertThat(read(new PosixAcl(DIR, OWNER, GROUP, 0600)))
                .containsExactly(named(
                        allow(AclKind.USER, DIR, "1000"), "pascal"));
    }

    @Test
    void testEveryoneButTheGroup() {
        // 0604: the group matches before "others", so its members may not
        // read, even though everyone else may
        assertThat(read(new PosixAcl(DIR, OWNER, GROUP, 0604)))
                .containsExactlyInAnyOrder(
                        named(allow(AclKind.USER, DIR, "1000"), "pascal"),
                        named(deny(AclKind.GROUP, DIR, "100"), "staff"),
                        allowSpecial(AclEntry.SPECIAL_EVERYONE));
    }

    @Test
    void testOwnerWithoutReadIsDeniedWhenTheGroupMayRead() {
        // 0040: the owner matches first and may not read
        assertThat(read(new PosixAcl(DIR, OWNER, GROUP, 0040)))
                .containsExactlyInAnyOrder(
                        named(deny(AclKind.USER, DIR, "1000"), "pascal"),
                        named(allow(AclKind.GROUP, DIR, "100"), "staff"));
    }

    @Test
    void testExtendedEntriesAndMask() {
        // getfacl: user::rw-, user:jane:r--, user:bob:rw-, group::r--,
        // group:eng:rw-, mask::-w-, other::---
        var acl = new PosixAcl(DIR, OWNER, GROUP, 0620)
                .ownerPerms(6)
                .user(new Principal("1001", "jane"), 4)
                .user(new Principal("1002", "bob"), 6)
                .groupPerms(4)
                .group(new Principal("200", "eng"), 6)
                .mask(2);
        // the mask takes away every named and group read
        assertThat(read(acl)).containsExactly(
                named(allow(AclKind.USER, DIR, "1000"), "pascal"));

        var withReadMask = new PosixAcl(DIR, OWNER, GROUP, 0640)
                .user(new Principal("1001", "jane"), 0)
                .group(new Principal("200", "eng"), 4)
                .groupPerms(0)
                .mask(4);
        // jane matches as a named user before her groups: denied
        assertThat(read(withReadMask)).containsExactlyInAnyOrder(
                named(allow(AclKind.USER, DIR, "1000"), "pascal"),
                named(deny(AclKind.USER, DIR, "1001"), "jane"),
                named(allow(AclKind.GROUP, DIR, "200"), "eng"));
        assertThat(withReadMask.hasExtendedEntries()).isTrue();
    }

    @Test
    void testDirectEntries() {
        var acl = new PosixAcl(DIR, OWNER, GROUP, 0644)
                .direct(new Direct(AclKind.USER, new Principal("501", "jo"),
                        false, PosixAcl.READ, false))
                .direct(new Direct(AclKind.GROUP, new Principal("12", null),
                        true, PosixAcl.WRITE, true));
        var entries = read(acl);
        assertThat(entries).contains(named(deny(AclKind.USER, DIR, "501"),
                "jo"));
        // the write-only entry does not concern reading
        assertThat(entries).noneMatch(e -> "12".equals(e.id()));

        var everyoneDenied = new PosixAcl(DIR, OWNER, GROUP, 0644)
                .direct(new Direct(AclKind.GROUP, new Principal("12", null),
                        false, PosixAcl.READ, true));
        assertThat(read(everyoneDenied)).contains(new AclEntry(
                AclKind.SPECIAL, null, AclEntry.SPECIAL_EVERYONE, null,
                AclAccess.DENY));
    }

    @Test
    void testTraverseRightAndAdmitsEverybody() {
        var open = new PosixAcl(DIR, OWNER, GROUP, 0755)
                .level("path", PosixAcl.EXECUTE);
        assertThat(PosixAcl.admitsEverybody(open)).isTrue();
        var closed = new PosixAcl(DIR, OWNER, GROUP, 0750)
                .level("path", PosixAcl.EXECUTE);
        assertThat(PosixAcl.admitsEverybody(closed)).isFalse();
        // 0745: everyone but the group may go through
        var groupDenied = new PosixAcl(DIR, OWNER, GROUP, 0745)
                .level("path", PosixAcl.EXECUTE);
        assertThat(PosixAcl.admitsEverybody(groupDenied)).isFalse();
    }

    @Test
    void testNativeDataAndOwners() {
        var acl = new PosixAcl(DIR, OWNER, GROUP, 0640)
                .user(new Principal("1001", "jane"), 4)
                .mask(4)
                .nativeEntry("default:user:jane:rwx");
        var data = acl.nativeData();
        assertThat(data).containsEntry("owner", "pascal")
                .containsEntry("group", "staff");
        assertThat(data.get("entries")).isEqualTo(List.of(
                "user::rw-", "user:jane:r--", "group::r--", "mask::r--",
                "other::---"));
        assertThat(data.get("extended"))
                .isEqualTo(List.of("default:user:jane:rwx"));
        assertThat(acl.owners()).containsExactly(new AclEntry(
                AclKind.USER, DIR, "1000", "pascal", null));
        assertThat(new PosixAcl(DIR, null, null, 0).owners()).isEmpty();
    }

    @Test
    void testParsing() {
        assertThat(PosixAcl.parseMode("755")).isEqualTo(0755);
        assertThat(PosixAcl.parseMode("1777")).isEqualTo(0777);
        assertThat(PosixAcl.parseMode("rwxr-x---")).isEqualTo(0750);
        assertThat(PosixAcl.parseMode("rwxr-x--x+")).isEqualTo(0751);
        assertThatThrownBy(() -> PosixAcl.parseMode("rw"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PosixAcl.parseMode(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PosixAcl.parsePerms("r-x")).isEqualTo(5);
        assertThat(PosixAcl.parsePerms(null)).isZero();
        assertThat(PosixAcl.mode(java.nio.file.attribute.PosixFilePermissions
                .fromString("rwxr-x--x"))).isEqualTo(0751);
    }

    @Test
    void testTraverseLevels() throws IOException {
        var levels = new TraverseLevels<String>();
        var reads = new AtomicInteger();
        TraverseLevels.FolderReader<String> reader = folder -> {
            reads.incrementAndGet();
            return switch (folder) {
                case "/" -> new PosixAcl(DIR, OWNER, GROUP, 0755)
                        .level("path", PosixAcl.EXECUTE);
                case "/a", "/a/b" -> new PosixAcl(DIR, OWNER, GROUP, 0750)
                        .level("path", PosixAcl.EXECUTE);
                default -> throw new IOException("denied: " + folder);
            };
        };

        var result = levels.levels(List.of("/", "/a", "/a/b"), reader);
        // the root lets everybody through; /a and /a/b say the same
        assertThat(result).singleElement()
                .extracting(AclLevel::name).isEqualTo("path");
        assertThat(reads).hasValue(3);

        // read once per crawl
        levels.levels(List.of("/", "/a"), reader);
        assertThat(reads).hasValue(3);

        // an unreadable folder fails, and is tried again next time
        List<String> withBad = new ArrayList<>(List.of("/", "/x"));
        assertThatThrownBy(() -> levels.levels(withBad, reader))
                .hasMessageContaining("/x");
        assertThatThrownBy(() -> levels.levels(withBad, reader))
                .hasMessageContaining("/x");
        assertThat(reads).hasValue(5);

        levels.clear();
        levels.levels(List.of("/"), reader);
        assertThat(reads).hasValue(6);
    }
}
