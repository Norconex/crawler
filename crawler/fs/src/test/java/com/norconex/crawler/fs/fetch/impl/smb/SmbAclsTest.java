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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.committer.core.acl.AclAccess;
import com.norconex.committer.core.acl.AclCodec;
import com.norconex.committer.core.acl.AclEntry;
import com.norconex.committer.core.acl.AclKind;
import com.norconex.committer.core.acl.AclLevel;
import com.norconex.committer.core.acl.AclStatus;
import com.norconex.commons.lang.map.Properties;
import com.norconex.crawler.core.junit.WithLogLevel;
import com.norconex.crawler.fs.fetch.acl.AclFetchMode;

import jcifs.ACE;
import jcifs.CIFSContext;
import jcifs.SID;

@Timeout(30)
class SmbAclsTest {

    private static SID sid(String text, int type, String domain,
            String account) {
        var sid = mock(SID.class);
        when(sid.toString()).thenReturn(text);
        when(sid.getType()).thenReturn(type);
        when(sid.getDomainName()).thenReturn(domain);
        when(sid.getAccountName()).thenReturn(account);
        return sid;
    }

    private static ACE ace(SID sid, boolean allow, int mask, int flags) {
        var ace = mock(ACE.class);
        when(ace.getSID()).thenReturn(sid);
        when(ace.isAllow()).thenReturn(allow);
        when(ace.getAccessMask()).thenReturn(mask);
        when(ace.getFlags()).thenReturn(flags);
        return ace;
    }

    private static final SID SALES = sid("S-1-5-21-1-2-3-1104",
            SID.SID_TYPE_DOM_GRP, "CORP", "Sales");
    private static final SID JACK = sid("S-1-5-21-1-2-3-1105",
            SID.SID_TYPE_USER, "CORP", "jack");
    private static final SID EVERYONE = sid("S-1-1-0",
            SID.SID_TYPE_WKN_GRP, null, "Everyone");
    private static final SID ADMINS = sid("S-1-5-32-544",
            SID.SID_TYPE_ALIAS, "BUILTIN", "Administrators");
    private static final SID UNKNOWN = sid("S-1-5-21-9-9-9-1500",
            SID.SID_TYPE_UNKNOWN, null, null);

    @Test
    void testLevel() {
        var aces = new ACE[] {
                ace(SALES, true, 0x1200a9, 0),
                ace(JACK, false, ACE.FILE_READ_DATA, ACE.FLAGS_INHERITED),
                ace(EVERYONE, true, ACE.GENERIC_READ, 0),
                ace(ADMINS, true, ACE.GENERIC_ALL, ACE.FLAGS_INHERIT_ONLY),
                ace(UNKNOWN, true, ACE.FILE_READ_DATA, 0),
                ace(JACK, true, ACE.FILE_WRITE_DATA, 0) };

        var level = SmbAcls.level("file", aces, SmbAcls.READ_RIGHTS);

        assertThat(level.entries()).containsExactlyInAnyOrder(
                new AclEntry(AclKind.GROUP, "ad:CORP", "S-1-5-21-1-2-3-1104",
                        "CORP\\Sales", AclAccess.ALLOW),
                new AclEntry(AclKind.USER, "ad:CORP", "S-1-5-21-1-2-3-1105",
                        "CORP\\jack", AclAccess.DENY),
                AclEntry.allowSpecial(AclEntry.SPECIAL_EVERYONE),
                new AclEntry(AclKind.UNDEFINED, "ad:S-1-5-21-9-9-9",
                        "S-1-5-21-9-9-9-1500", null, AclAccess.ALLOW));
        assertThat(SmbAcls.entry(ADMINS, AclAccess.ALLOW)).isEqualTo(
                new AclEntry(AclKind.GROUP, "windows:BUILTIN", "S-1-5-32-544",
                        "BUILTIN\\Administrators", AclAccess.ALLOW));
        assertThat(SmbAcls.nativeEntries(aces)).hasSize(6)
                .first().asString()
                .isEqualTo("ALLOW:S-1-5-21-1-2-3-1104:CORP\\Sales"
                        + ":0x1200a9:0x0");
    }

    @Test
    @WithLogLevel(value = "OFF", classes = SmbFetcher.class)
    void testFetcherLevels() throws Exception {
        var fetcher = new SmbFetcher();
        fetcher.getConfiguration()
                .setAcl(AclFetchMode.EXTENDED)
                .setLinuxServer(true);
        var provider = spy(new SmbFileSystemProvider());
        var field = SmbFetcher.class.getDeclaredField("provider");
        field.setAccessible(true);
        field.set(fetcher, provider);
        var fs = provider.getOrCreateFileSystem(
                URI.create("smb://host/share/"), mock(CIFSContext.class));
        var file = fs.getPath("/share/dir/file.txt");

        var allowSales = new ACE[] { ace(SALES, true, 0x1200a9, 0) };
        var everyone = new ACE[] { ace(EVERYONE, true, ACE.GENERIC_ALL, 0) };
        doReturn(allowSales).when(provider).getShareAcl(any(), eq(true));
        doReturn(everyone).when(provider)
                .getAcl(eq(fs.getPath("/share")), anyBoolean());
        doReturn(allowSales).when(provider)
                .getAcl(eq(fs.getPath("/share/dir")), anyBoolean());
        doReturn(allowSales).when(provider).getAcl(eq(file), anyBoolean());
        var meta = new Properties();

        fetcher.fetchAcl(file, meta);

        var acl = AclCodec.read(meta).orElseThrow();
        assertThat(acl.status()).isEqualTo(AclStatus.COMPLETE);
        // the share root lets everybody through: no level for it
        assertThat(acl.levels()).extracting(AclLevel::name)
                .containsExactly("share", "path", "file");

        // a share whose permissions cannot be read makes the ACL partial
        var other = fs.getPath("/other/file.txt");
        doThrow(new IOException("access denied")).when(provider)
                .getShareAcl(eq(other), anyBoolean());
        doReturn(everyone).when(provider)
                .getAcl(eq(fs.getPath("/other")), anyBoolean());
        doReturn(allowSales).when(provider).getAcl(eq(other), anyBoolean());
        var otherMeta = new Properties();
        fetcher.fetchAcl(other, otherMeta);
        var partial = AclCodec.read(otherMeta).orElseThrow();
        assertThat(partial.status()).isEqualTo(AclStatus.PARTIAL);
        assertThat(partial.unread()).singleElement().asString()
                .contains("share permissions").contains("access denied");
    }

    private static SmbPath any() {
        return org.mockito.ArgumentMatchers.any(SmbPath.class);
    }
}
