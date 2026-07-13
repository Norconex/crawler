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
package com.norconex.crawler.fs.fetch.impl.azureblob;

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

import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.norconex.crawler.core.fetch.Fetcher;
import com.norconex.crawler.fs.FsTestUtil;
import com.norconex.crawler.fs.fetch.impl.AbstractFileFetcherTest;

@Testcontainers(disabledWithoutDocker = true)
@Timeout(180)
class AzureBlobFetcherIT extends AbstractFileFetcherTest {

    private static final String ACCOUNT = "devstoreaccount1";
    private static final String ACCOUNT_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";
    private static final String CONTAINER = "test-container";

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> AZURITE =
            new GenericContainer<>(
                    DockerImageName.parse(
                            "mcr.microsoft.com/azure-storage/azurite:latest"))
                                    .withCommand(
                                            "azurite-blob",
                                            "--blobHost",
                                            "0.0.0.0",
                                            "--blobPort",
                                            "10000",
                                            "--skipApiVersionCheck")
                                    .withExposedPorts(10000);

    private static String endpoint;

    @BeforeAll
    static void uploadTestFiles() throws IOException {
        endpoint = "http://%s:%s/%s".formatted(
                AZURITE.getHost(), AZURITE.getFirstMappedPort(), ACCOUNT);

        var containerClient = new BlobContainerClientBuilder()
                .endpoint(endpoint)
                .containerName(CONTAINER)
                .credential(new StorageSharedKeyCredential(
                        ACCOUNT, ACCOUNT_KEY))
                .buildClient();
        containerClient.create();

        var root = Path.of(FsTestUtil.TEST_FS_PATH);
        try (Stream<Path> files = Files.walk(root)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                var key = root.relativize(file).toString().replace('\\', '/');
                containerClient.getBlobClient(key).uploadFromFile(
                        file.toString());
            }
        }
    }

    @Override
    protected Fetcher fetcher() {
        var fetcher = new AzureBlobFetcher();
        fetcher.getConfiguration()
                .setEndpoint(endpoint)
                .getCredentials()
                .setUsername(ACCOUNT)
                .setPassword(ACCOUNT_KEY);
        return fetcher;
    }

    @Override
    protected String getStartPath() {
        return "azblob://%s/%s".formatted(ACCOUNT, CONTAINER);
    }
}
