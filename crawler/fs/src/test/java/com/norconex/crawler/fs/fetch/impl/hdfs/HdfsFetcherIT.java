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

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;

import com.norconex.crawler.core.fetch.Fetcher;
import com.norconex.crawler.fs.FsTestUtil;
import com.norconex.crawler.fs.fetch.impl.AbstractFileFetcherTest;

@Timeout(60)
class HdfsFetcherIT extends AbstractFileFetcherTest {

    private static HdfsTestServer server;

    @BeforeAll
    static void startServer() throws IOException {
        server = new HdfsTestServer(Path.of(FsTestUtil.TEST_FS_PATH));
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @Override
    protected Fetcher fetcher() {
        return new HdfsFetcher();
    }

    @Override
    protected String getStartPath() {
        return "webhdfs://localhost:" + server.getLocalPort();
    }
}
