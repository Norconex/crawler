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
package com.norconex.crawler.fs.fetch.impl.adlsgen2;

import static com.norconex.crawler.core.fetch.FetchDirective.DOCUMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.attribute.FileTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.azure.storage.common.StorageSharedKeyCredential;
import com.azure.storage.file.datalake.DataLakeFileClient;
import com.azure.storage.file.datalake.DataLakeFileSystemClient;
import com.azure.storage.file.datalake.DataLakeFileSystemClientBuilder;
import com.azure.storage.file.datalake.DataLakeDirectoryClient;
import com.azure.storage.file.datalake.models.DataLakeStorageException;
import com.azure.storage.file.datalake.models.PathAccessControl;
import com.azure.storage.file.datalake.models.PathAccessControlEntry;
import com.azure.storage.file.datalake.models.PathPermissions;
import com.azure.storage.file.datalake.models.PathProperties;
import com.norconex.committer.core.acl.AclCodec;
import com.norconex.committer.core.acl.AclEntry;
import com.norconex.committer.core.acl.AclKind;
import com.norconex.committer.core.acl.AclLevel;
import com.norconex.committer.core.acl.AclScope;
import com.norconex.committer.core.acl.AclStatus;
import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.map.Properties;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.fs.FsTestUtil;
import com.norconex.crawler.fs.fetch.acl.AclFetchMode;
import com.norconex.crawler.fs.doc.FsDocMetadata;
import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.importer.doc.Doc;

@Timeout(30)
class AdlsGen2FetcherTest {

    @Test
    void testAcceptRequest() {
        var f = new AdlsGen2Fetcher();
        assertThat(f.acceptRequest(new FileFetchRequest(
                new Doc("abfs://filesystem@account.dfs.core.windows.net/path"),
                DOCUMENT))).isTrue();
        assertThat(f.acceptRequest(new FileFetchRequest(
                new Doc("abfss://filesystem@account.dfs.core.windows.net/path"),
                DOCUMENT))).isTrue();
        assertThat(f.acceptRequest(new FileFetchRequest(
                new Doc("azblob://account/container/path"),
                DOCUMENT)))
                        .isFalse();
    }

    @Test
    void testWriteRead() {
        assertThatNoException().isThrownBy(
                () -> BeanMapper.DEFAULT.assertWriteRead(
                        FsTestUtil.randomize(
                                AdlsGen2Fetcher.class)));
    }

    @Test
    void testBuildClientWithSharedKeyCredentials() {
        var clientBuilder = mock(DataLakeFileSystemClientBuilder.class);
        var client = mock(DataLakeFileSystemClient.class);
        when(clientBuilder.endpoint(any())).thenReturn(clientBuilder);
        when(clientBuilder.fileSystemName(any()))
                .thenReturn(clientBuilder);
        when(clientBuilder.credential(
                any(StorageSharedKeyCredential.class)))
                        .thenReturn(clientBuilder);
        when(clientBuilder.buildClient()).thenReturn(client);

        var f = new AdlsGen2Fetcher() {
            @Override
            DataLakeFileSystemClientBuilder newClientBuilder() {
                return clientBuilder;
            }
        };
        f.getConfiguration().getCredentials()
                .setUsername("account")
                .setPassword("secret");
        assertThatNoException().isThrownBy(() -> {
            var builtClient = f.buildClient(AdlsGen2Location.from(
                    java.net.URI.create(
                            "abfss://filesystem@account.dfs.core.windows.net/path")));
            assertThat(builtClient).isSameAs(client);
        });
        verify(clientBuilder)
                .endpoint(eq("https://account.dfs.core.windows.net"));
        verify(clientBuilder).fileSystemName(eq("filesystem"));
        verify(clientBuilder).credential(
                any(StorageSharedKeyCredential.class));
        verify(clientBuilder).buildClient();
    }

    @Test
    void testBuildClientRejectsMismatchedAccount() {
        var f = new AdlsGen2Fetcher();
        f.getConfiguration().getCredentials()
                .setUsername("otheraccount")
                .setPassword("secret");
        assertThatThrownBy(() -> f.buildClient(AdlsGen2Location.from(
                java.net.URI.create(
                        "abfss://filesystem@account.dfs.core.windows.net/path"))))
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining(
                                        "reference account");
    }

    @Test
    void testBuildClientWithSasTokenAndCustomEndpoint() {
        var clientBuilder = mock(DataLakeFileSystemClientBuilder.class);
        var client = mock(DataLakeFileSystemClient.class);
        when(clientBuilder.endpoint(any())).thenReturn(clientBuilder);
        when(clientBuilder.fileSystemName(any()))
                .thenReturn(clientBuilder);
        when(clientBuilder.sasToken(any())).thenReturn(clientBuilder);
        when(clientBuilder.buildClient()).thenReturn(client);

        var f = new AdlsGen2Fetcher() {
            @Override
            DataLakeFileSystemClientBuilder newClientBuilder() {
                return clientBuilder;
            }
        };
        f.getConfiguration()
                .setEndpoint("https://custom.endpoint.example");
        f.getConfiguration().setSasToken("?sig=abc123");

        var builtClient =
                f.buildClient(AdlsGen2Location.from(URI.create(
                        "abfss://filesystem@Account.dfs.core.windows.net/path")));

        assertThat(builtClient).isSameAs(client);
        verify(clientBuilder).endpoint(
                eq("https://custom.endpoint.example"));
        verify(clientBuilder).fileSystemName(eq("filesystem"));
        verify(clientBuilder).sasToken(eq("sig=abc123"));
        verify(clientBuilder, never())
                .credential(any(StorageSharedKeyCredential.class));
    }

    @Test
    void testBuildClientWithSasTokenAsPasswordOfCredentialsWithoutUsername() {
        var clientBuilder = mock(DataLakeFileSystemClientBuilder.class);
        var client = mock(DataLakeFileSystemClient.class);
        when(clientBuilder.endpoint(any())).thenReturn(clientBuilder);
        when(clientBuilder.fileSystemName(any()))
                .thenReturn(clientBuilder);
        when(clientBuilder.sasToken(any())).thenReturn(clientBuilder);
        when(clientBuilder.buildClient()).thenReturn(client);

        var f = new AdlsGen2Fetcher() {
            @Override
            DataLakeFileSystemClientBuilder newClientBuilder() {
                return clientBuilder;
            }
        };
        f.getConfiguration().getCredentials().setPassword("?sig=abc123");

        var builtClient =
                f.buildClient(AdlsGen2Location.from(URI.create(
                        "abfss://filesystem@account.dfs.core.windows.net/path")));

        assertThat(builtClient).isSameAs(client);
        verify(clientBuilder).sasToken("sig=abc123");
        verify(clientBuilder, never())
                .credential(any(StorageSharedKeyCredential.class));
    }

    @Test
    void testResolvePathAndShutdown() throws Exception {
        var client = mock(DataLakeFileSystemClient.class);
        var fetcher = new AdlsGen2Fetcher() {
            @Override
            DataLakeFileSystemClient
                    buildClient(AdlsGen2Location location) {
                return client;
            }
        };
        var session = mock(CrawlerSession.class);

        var filePath = fetcher.resolvePath(
                "abfss://filesystem@account.dfs.core.windows.net/dir/file.txt");
        var rootPath = fetcher.resolvePath(
                "abfss://filesystem@account.dfs.core.windows.net");

        assertThat(filePath).hasToString("/dir/file.txt");
        assertThat(rootPath).hasToString("/");
        assertThat(provider(fetcher).openFileSystems()).hasSize(1);

        fetcher.fetcherShutdown(session);
        assertThat(provider(fetcher).openFileSystems()).isEmpty();
    }

    @Test
    void testFetchAclWritesTheStandard() throws Exception {
        var fetcher = new AdlsGen2Fetcher();
        fetcher.getConfiguration().setAcl(AclFetchMode.EXTENDED);
        var provider = provider(fetcher);
        var client = mock(DataLakeFileSystemClient.class);
        var location = AdlsGen2Location.from(URI.create(
                "abfss://filesystem@account.dfs.core.windows.net/root"));
        var fs = provider.getOrCreateFileSystem(location, loc -> client);
        var path = fs.getPath("/dir/file.txt");
        // root lets everybody through: no level for it
        mockAccessControl(client, "", "rwxr-x--x");
        // dir lets only its owner and group through
        mockAccessControl(client, "dir", "rwxr-x---");
        mockAccessControl(client, "dir/file.txt",
                "user::rw-,user:oid-jane:r--,group::r--,mask::r--,"
                        + "other::---,default:user:oid-bob:rwx");
        var meta = new Properties();

        fetcher.fetchAcl(path, meta);

        var acl = AclCodec.read(meta).orElseThrow();
        assertThat(acl.status()).isEqualTo(AclStatus.COMPLETE);
        assertThat(acl.levels()).extracting(AclLevel::name)
                .containsExactly("path", "file");
        assertThat(acl.levels().get(0).entries()).containsExactlyInAnyOrder(
                AclEntry.allow(AclKind.USER, "entra:account", "owner-oid"),
                AclEntry.allow(AclKind.GROUP, "entra:account", "group-oid"));
        assertThat(acl.levels().get(1).entries()).containsExactlyInAnyOrder(
                AclEntry.allow(AclKind.USER, "entra:account", "owner-oid"),
                AclEntry.allow(AclKind.USER, "entra:account", "oid-jane"),
                AclEntry.allow(AclKind.GROUP, "entra:account", "group-oid"));
        assertThat(acl.owners()).containsExactly(
                AclEntry.owner(AclKind.USER, "entra:account", "owner-oid"));
        assertThat(acl.nativeData().get("extended").toString())
                .contains("default:user:oid-bob:rwx");
    }

    @Test
    void testFetchAclBasicLeavesOutNamedEntries() throws Exception {
        var fetcher = new AdlsGen2Fetcher();
        fetcher.getConfiguration().setAcl(AclFetchMode.BASIC);
        var provider = provider(fetcher);
        var client = mock(DataLakeFileSystemClient.class);
        var location = AdlsGen2Location.from(URI.create(
                "abfss://filesystem@account.dfs.core.windows.net/root"));
        var fs = provider.getOrCreateFileSystem(location, loc -> client);
        mockAccessControl(client, "", "rwxr-xr-x");
        mockAccessControl(client, "file.txt",
                "user::rw-,user:oid-jane:r--,group::---,mask::r--,other::r--");
        var meta = new Properties();

        fetcher.fetchAcl(fs.getPath("/file.txt"), meta);

        var acl = AclCodec.read(meta).orElseThrow();
        assertThat(acl.scope()).isEqualTo(AclScope.BASIC);
        assertThat(acl.levels()).singleElement().satisfies(level -> assertThat(
                level.entries()).containsExactlyInAnyOrder(
                        AclEntry.allow(AclKind.USER, "entra:account",
                                "owner-oid"),
                        AclEntry.deny(AclKind.GROUP, "entra:account",
                                "group-oid"),
                        AclEntry.allowSpecial(AclEntry.SPECIAL_EVERYONE)));
    }

    @Test
    void testFetchAclUnreadableIsNone() throws Exception {
        var fetcher = new AdlsGen2Fetcher();
        fetcher.getConfiguration().setAcl(AclFetchMode.EXTENDED);
        var provider = provider(fetcher);
        var client = mock(DataLakeFileSystemClient.class);
        var location = AdlsGen2Location.from(URI.create(
                "abfss://filesystem@account.dfs.core.windows.net/root"));
        var fs = provider.getOrCreateFileSystem(location, loc -> client);
        var dirClient = mock(DataLakeDirectoryClient.class);
        when(client.getDirectoryClient(any())).thenReturn(dirClient);
        when(dirClient.getAccessControl()).thenThrow(
                mock(DataLakeStorageException.class));
        var meta = new Properties();

        fetcher.fetchAcl(fs.getPath("/file.txt"), meta);

        assertThat(AclCodec.read(meta).orElseThrow().status())
                .isEqualTo(AclStatus.NONE);
    }

    @Test
    void testAclConfigDefaultsToNone() {
        var f = new AdlsGen2Fetcher();
        assertThat(f.getConfiguration().getAcl()).isEqualTo(AclFetchMode.NONE);
        f.getConfiguration().setAcl(AclFetchMode.BASIC);
        assertThat(f.getConfiguration().getAcl())
                .isEqualTo(AclFetchMode.BASIC);
    }

    private static void mockAccessControl(
            DataLakeFileSystemClient client, String name, String acl) {
        var dirClient = mock(DataLakeDirectoryClient.class);
        List<PathAccessControlEntry> entries;
        PathPermissions perms = null;
        if (acl.contains("::")) {
            entries = PathAccessControlEntry.parseList(acl);
        } else {
            entries = List.of();
            perms = PathPermissions.parseSymbolic(acl);
        }
        when(client.getDirectoryClient(name)).thenReturn(dirClient);
        when(dirClient.getAccessControl()).thenReturn(new PathAccessControl(
                entries, perms, "group-oid", "owner-oid"));
    }

    private static AdlsGen2FileSystemProvider
            provider(AdlsGen2Fetcher fetcher)
                    throws Exception {
        var field = AdlsGen2Fetcher.class.getDeclaredField("provider");
        field.setAccessible(true);
        return (AdlsGen2FileSystemProvider) field.get(fetcher);
    }
}
