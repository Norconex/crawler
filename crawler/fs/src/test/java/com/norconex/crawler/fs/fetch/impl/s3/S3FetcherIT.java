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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;
import com.norconex.crawler.core.fetch.Fetcher;
import com.norconex.crawler.fs.FsTestUtil;
import com.norconex.crawler.fs.fetch.impl.AbstractFileFetcherTest;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Testcontainers(disabledWithoutDocker = true)
class S3FetcherIT extends AbstractFileFetcherTest {

    @TempDir
    Path tempDir;

    private static final String BUCKET = "test-bucket";
    // S3Mock does not validate credentials; any values will do.
    private static final String ACCESS_KEY = "test-access-key";
    private static final String SECRET_KEY = "test-secret-key";

    // S3Mock replaces minio/minio here: MinIO's own images are no longer
    // freely pullable (gone from Docker Hub, gated on quay.io). S3Mock is a
    // small (~80MB vs LocalStack's ~430MB), actively maintained, S3-only test
    // double built for exactly this. The container's own wait strategy
    // (GET /favicon.ico) covers readiness, so that doesn't need doing by
    // hand here — but withInitialBuckets(BUCKET) proved racy against it
    // (NoSuchBucket on the very first upload), so the bucket is still
    // created explicitly below, same as the old MinIO version did.
    @SuppressWarnings("resource")
    @Container
    static final S3MockContainer S3MOCK = new S3MockContainer("latest");

    private static String endpoint;

    @BeforeAll
    static void uploadTestFiles() throws IOException {
        endpoint = S3MOCK.getHttpEndpoint();
        try (var client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(
                        StaticCredentialsProvider
                                .create(
                                        AwsBasicCredentials
                                                .create(
                                                        ACCESS_KEY,
                                                        SECRET_KEY)))
                .build()) {
            client.createBucket(b -> b.bucket(BUCKET));
            var root = Path.of(FsTestUtil.TEST_FS_PATH);
            try (Stream<Path> files = Files.walk(root)) {
                for (var file : files
                        .filter(Files::isRegularFile)
                        .toList()) {
                    var key = root.relativize(file)
                            .toString()
                            .replace('\\', '/');
                    client.putObject(
                            b -> b.bucket(BUCKET)
                                    .key(key),
                            file);
                }
            }
        }
    }

    @Override
    protected Fetcher fetcher() {
        return createFetcher(true);
    }

    @Test
    void testFetchFilesWithDefaultCredentialChain() throws Exception {
        var oldAccessKey = System.getProperty("aws.accessKeyId");
        var oldSecretKey = System.getProperty("aws.secretAccessKey");
        try {
            System.setProperty("aws.accessKeyId", ACCESS_KEY);
            System.setProperty("aws.secretAccessKey", SECRET_KEY);

            var mem = FsTestUtil.crawlWithFetcher(
                    tempDir, createFetcher(false),
                    getStartPath());
            assertThat(mem.getUpsertCount()).isEqualTo(8);
        } finally {
            restoreProperty("aws.accessKeyId", oldAccessKey);
            restoreProperty("aws.secretAccessKey", oldSecretKey);
        }
    }

    private Fetcher createFetcher(boolean withExplicitCredentials) {
        var fetcher = new S3Fetcher();
        fetcher.getConfiguration()
                .setEndpoint(endpoint)
                .setRegion(Region.US_EAST_1.id())
                .setForcePathStyle(true);
        if (withExplicitCredentials) {
            fetcher.getConfiguration()
                    .getCredentials()
                    .setUsername(ACCESS_KEY)
                    .setPassword(SECRET_KEY);
        }
        return fetcher;
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
            return;
        }
        System.setProperty(key, value);
    }

    @Override
    protected String getStartPath() {
        return "s3://" + BUCKET;
    }
}
