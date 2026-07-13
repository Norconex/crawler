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
package com.norconex.crawler.fs.fetch.impl.smb;

import java.io.File;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import com.norconex.crawler.core.fetch.Fetcher;
import com.norconex.crawler.fs.FsTestUtil;
import com.norconex.crawler.fs.fetch.impl.AbstractFileFetcherTest;

@Testcontainers(disabledWithoutDocker = true)
@Timeout(120)
class SmbFetcherIT extends AbstractFileFetcherTest {

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> SAMBA =
            new GenericContainer<>("adevur/easy-samba:latest")
                    .withExposedPorts(445)
                    .withFileSystemBind(
                            new File(FsTestUtil.TEST_FS_PATH).getAbsolutePath(),
                            "/share/joefiles",
                            BindMode.READ_ONLY)
                    .withCopyToContainer(
                            MountableFile
                                    .forClasspathResource("/smb/config.json"),
                            "/share/config/config.json");

    @BeforeAll
    static void beforeAll() {
        SAMBA.start();
    }

    @AfterAll
    static void afterAll() {
        SAMBA.stop();
    }

    @Override
    protected Fetcher fetcher() {
        var fetcher = new SmbFetcher();
        fetcher.getConfiguration()
                .setDomain("WORKGROUP")
                .getCredentials()
                .setUsername("joe")
                .setPassword("joepwd");
        return fetcher;
    }

    @Override
    protected String getStartPath() {
        var host = SAMBA.getHost();
        var port = SAMBA.getFirstMappedPort();
        return "smb://%s:%s/joefiles".formatted(host, port);
    }
}
