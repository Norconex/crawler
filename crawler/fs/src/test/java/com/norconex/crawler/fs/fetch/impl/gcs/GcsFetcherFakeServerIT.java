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
package com.norconex.crawler.fs.fetch.impl.gcs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.HttpStorageOptions;
import com.norconex.crawler.core.fetch.Fetcher;
import com.norconex.crawler.fs.FsTestUtil;
import com.norconex.crawler.fs.fetch.impl.AbstractFileFetcherTest;

/**
 * Runs the common file fetcher tests against a fake of Google Cloud Storage
 * (fake-gcs-server), so that what the real client and the NIO provider do is
 * exercised, not mocks of them.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(180)
class GcsFetcherFakeServerIT extends AbstractFileFetcherTest {

    private static final String BUCKET = "test-bucket";

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> GCS =
            new GenericContainer<>(
                    DockerImageName.parse("fsouza/fake-gcs-server:1.54.0"))
                            .withCommand("-scheme", "http", "-port", "4443")
                            .withExposedPorts(4443);

    private static String endpoint;

    @BeforeAll
    static void uploadTestFiles() throws IOException {
        endpoint = "http://%s:%s".formatted(
                GCS.getHost(), GCS.getFirstMappedPort());
        var storage = HttpStorageOptions.newBuilder()
                .setHost(endpoint)
                .setProjectId("test")
                .setCredentials(NoCredentials.getInstance())
                .build()
                .getService();
        storage.create(BucketInfo.of(BUCKET));

        var root = Path.of(FsTestUtil.TEST_FS_PATH);
        try (Stream<Path> files = Files.walk(root)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var key = root.relativize(file).toString()
                        .replace(File.separatorChar, '/');
                storage.create(
                        BlobInfo.newBuilder(BUCKET, key).build(),
                        Files.readAllBytes(file));
            }
        }
    }

    @Override
    protected Fetcher fetcher() {
        var fetcher = new GcsFetcher();
        fetcher.getConfiguration().setEndpoint(endpoint);
        return fetcher;
    }

    @Override
    protected String getStartPath() {
        return "gs://" + BUCKET;
    }
}
