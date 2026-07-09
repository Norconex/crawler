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
package com.norconex.crawler.fs.fetch.impl.s3;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.norconex.crawler.core.fetch.Fetcher;
import com.norconex.crawler.fs.FsTestUtil;
import com.norconex.crawler.fs.fetch.impl.AbstractFileFetcherTest;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Testcontainers(disabledWithoutDocker = true)
class S3FetcherIT extends AbstractFileFetcherTest {

    private static final String BUCKET = "test-bucket";
    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse("minio/minio:latest"))
                    .withCommand("server", "/data")
                    .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                    .withExposedPorts(9000)
                    .waitingFor(
                            Wait.forHttp("/minio/health/ready")
                                    .forPort(9000));

    private static String endpoint;

    @BeforeAll
    static void uploadTestFiles() throws IOException {
        endpoint = "http://%s:%s".formatted(
                MINIO.getHost(), MINIO.getFirstMappedPort());
        try (var client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(
                                        ACCESS_KEY, SECRET_KEY)))
                .build()) {
            client.createBucket(b -> b.bucket(BUCKET));
            var root = Path.of(FsTestUtil.TEST_FS_PATH);
            try (Stream<Path> files = Files.walk(root)) {
                for (var file : files.filter(Files::isRegularFile).toList()) {
                    var key = root.relativize(file).toString()
                            .replace('\\', '/');
                    client.putObject(
                            b -> b.bucket(BUCKET).key(key),
                            file);
                }
            }
        }
    }

    @Override
    protected Fetcher fetcher() {
        var fetcher = new S3Fetcher();
        fetcher.getConfiguration()
                .setEndpoint(endpoint)
                .setRegion(Region.US_EAST_1.id())
                .setForcePathStyle(true)
                .getCredentials()
                .setUsername(ACCESS_KEY)
                .setPassword(SECRET_KEY);
        return fetcher;
    }

    @Override
    protected String getStartPath() {
        return "s3://" + BUCKET;
    }
}
