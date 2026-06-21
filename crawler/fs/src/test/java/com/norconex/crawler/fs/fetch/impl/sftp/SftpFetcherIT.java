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
package com.norconex.crawler.fs.fetch.impl.sftp;

import java.io.File;
import java.io.IOException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.norconex.crawler.core.fetch.Fetcher;
import com.norconex.crawler.fs.FsTestUtil;
import com.norconex.crawler.fs.fetch.impl.AbstractFileFetcherTest;

@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class SftpFetcherIT extends AbstractFileFetcherTest {

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> SFTP =
            new GenericContainer<>("atmoz/sftp:latest")
                    .withExposedPorts(22)
                    .withFileSystemBind(
                            new File(FsTestUtil.TEST_FS_PATH).getAbsolutePath(),
                            "/home/user/download",
                            BindMode.READ_ONLY)
                    .withCommand("user:unsecure:1001::::::download");

    @BeforeAll
    static void start() throws IOException {
        SFTP.start();
    }

    @AfterAll
    static void stop() {
        SFTP.stop();
    }

    @Override
    protected Fetcher fetcher() {
        var fetcher = new SftpFetcher();
        fetcher.getConfiguration()
                .getCredentials()
                .setUsername("user")
                .setPassword("unsecure");
        return fetcher;
    }

    @Override
    protected String getStartPath() {
        var host = SFTP.getHost();
        var port = SFTP.getFirstMappedPort();
        return "sftp://%s:%s/download".formatted(host, port);
    }
}
