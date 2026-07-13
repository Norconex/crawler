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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.azure.storage.common.StorageSharedKeyCredential;
import com.azure.storage.file.datalake.DataLakeFileClient;
import com.azure.storage.file.datalake.DataLakeFileSystemClient;
import com.azure.storage.file.datalake.DataLakeFileSystemClientBuilder;
import com.azure.storage.file.datalake.models.PathProperties;
import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.map.Properties;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.fs.FsTestUtil;
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
        void testFetchMetadataConsumesAclAndSetsEnabledMarker()
                        throws Exception {
                var fetcher = new AdlsGen2Fetcher();
                var provider = provider(fetcher);
                var client = mock(DataLakeFileSystemClient.class);
                var fileClient = mock(DataLakeFileClient.class);
                var props = mock(PathProperties.class);
                var location = AdlsGen2Location.from(URI.create(
                                "abfss://filesystem@account.dfs.core.windows.net/root"));
                var fs = provider.getOrCreateFileSystem(location,
                                loc -> client);
                var path = fs.getPath("/dir/file.txt");
                var doc = new Doc(
                                "abfss://filesystem@account.dfs.core.windows.net/dir/file.txt");
                var aclMeta = new Properties();
                aclMeta.add(FsDocMetadata.ACL + ".ACCESS.NOTYPE.", "user1");
                fs.aclCache().put(path.toString(), aclMeta);
                when(client.getFileClient("dir/file.txt"))
                                .thenReturn(fileClient);
                when(fileClient.exists()).thenReturn(true);
                when(fileClient.getProperties()).thenReturn(props);
                when(props.getFileSize()).thenReturn(3L);
                when(props.getLastModified()).thenReturn(
                                java.time.OffsetDateTime.now());

                fetcher.fetchMetadata(doc, path,
                                new AdlsGen2FileAttributes(false, 3L,
                                                FileTime.fromMillis(1)));

                assertThat(doc.getMetadata().getStrings(
                                FsDocMetadata.ACL + ".ACCESS.NOTYPE."))
                                                .contains("user1");
                assertThat(doc.getMetadata()
                                .getString(FsDocMetadata.ACL + ".enabled"))
                                                .isEqualTo("true");
                assertThat(fs.aclCache()).isEmpty();
        }

        @Test
        void testAclDisabledConfigToggle() {
                var f = new AdlsGen2Fetcher();
                assertThat(f.getConfiguration().isAclDisabled()).isFalse();
                f.getConfiguration().setAclDisabled(true);
                assertThat(f.getConfiguration().isAclDisabled()).isTrue();
        }

        private static AdlsGen2FileSystemProvider
                        provider(AdlsGen2Fetcher fetcher)
                                        throws Exception {
                var field = AdlsGen2Fetcher.class.getDeclaredField("provider");
                field.setAccessible(true);
                return (AdlsGen2FileSystemProvider) field.get(fetcher);
        }
}
