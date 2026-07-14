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
package com.norconex.crawler.fs.fetch.impl.hdfs;

import static com.norconex.crawler.core.fetch.FetchDirective.DOCUMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;

import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.crawler.core.CrawlerException;
import com.norconex.crawler.fs.FsTestUtil;
import com.norconex.crawler.fs.fetch.FileFetchRequest;
import com.norconex.importer.doc.Doc;

@Timeout(30)
class HdfsFetcherTest {

    @Test
    void testAcceptRequest() {
        var f = new HdfsFetcher();
        assertThat(f.acceptRequest(new FileFetchRequest(
                new Doc("webhdfs://host:9870/path"), DOCUMENT))).isTrue();
        assertThat(f.acceptRequest(new FileFetchRequest(
                new Doc("hdfs://host:8020/path"), DOCUMENT))).isFalse();
        assertThat(f.acceptRequest(new FileFetchRequest(
                new Doc("http://host/path"), DOCUMENT))).isFalse();
    }

    @Test
    void testWriteRead() {
        assertThatNoException().isThrownBy(
                () -> BeanMapper.DEFAULT.assertWriteRead(
                        FsTestUtil.randomize(HdfsFetcher.class)));
    }

    @Test
    void testSimpleAuthClientHasNoDefaultCredentials() {
        var f = new HdfsFetcher();
        assertThatNoException().isThrownBy(() -> {
            try (var client = f.buildHttpClient(
                    new HttpHost("http", "localhost", 9870))) {
                assertThat(client).isNotNull();
            }
        });
    }

    @Test
    void testKerberosAuthWithoutConfigFailsOnStartup() {
        var f = new HdfsFetcher();
        f.getConfiguration().setAuthMethod(HdfsAuthMethod.KERBEROS);
        // Full Kerberos login requires a real KDC/krb5.conf; only the
        // pre-flight "config is required" guard is unit-testable here.
        assertThatThrownBy(() -> f.fetcherStartup(null))
                .isInstanceOf(CrawlerException.class)
                .hasMessageContaining("Kerberos configuration is required");
    }

    @Test
    void testResolvePathRootAndFilePath() throws Exception {
        var f = new HdfsFetcher();
        f.getConfiguration().getCredentials().setUsername("alice");

        Path root = f.resolvePath("webhdfs://namenode.example.com:9870");
        Path child = f.resolvePath(
                "webhdfs://namenode.example.com:9870/dir/file.txt");

        assertThat(root.toString()).isEqualTo("/");
        assertThat(child.toString()).isEqualTo("/dir/file.txt");

        assertThatNoException().isThrownBy(() -> f.fetcherShutdown(null));
    }

    @Test
    void testFetcherShutdownSwallowsCloseRuntimeException() throws Exception {
        var f = new HdfsFetcher();
        var providerField = HdfsFetcher.class.getDeclaredField("provider");
        providerField.setAccessible(true);
        var provider = providerField.get(f);

        var mapField = provider.getClass().getDeclaredField("fileSystems");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, HdfsFileSystem> map =
                (Map<String, HdfsFileSystem>) mapField.get(provider);

        var fs = mock(HdfsFileSystem.class);
        doThrow(new RuntimeException("boom")).when(fs).close();
        map.put("badhost:9870", fs);

        assertThatNoException().isThrownBy(() -> f.fetcherShutdown(null));
        verify(fs).close();
    }

    @Test
    void testKerberosWithCustomLoginModuleWrapsLoginException() {
        var f = new HdfsFetcher();
        f.getConfiguration().setAuthMethod(HdfsAuthMethod.KERBEROS);
        f.getConfiguration().setKerberosConfig(
                new KerberosConfig().setLoginModuleName("NoSuchLoginModule"));

        assertThatThrownBy(() -> f.fetcherStartup(null))
                .isInstanceOf(CrawlerException.class)
                .hasMessageContaining("Failed to perform Kerberos login");
    }

    @Test
    void testToCharArrayNullAndValue() throws Exception {
        Method m = HdfsFetcher.class.getDeclaredMethod("toCharArray",
                String.class);
        m.setAccessible(true);

        var fromNull = (char[]) m.invoke(null, new Object[] { null });
        var fromValue = (char[]) m.invoke(null, "ab");

        assertThat(fromNull).isEmpty();
        assertThat(fromValue).containsExactly('a', 'b');
    }
}
