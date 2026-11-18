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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.sas.BlobContainerSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.common.StorageSharedKeyCredential;

/**
 * A SAS token read from the password of the credentials (no username), or
 * from {@code sasToken}, against Azurite.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(180)
class AzureBlobFetcherSasIT {

    private static final String ACCOUNT = "devstoreaccount1";
    private static final String ACCOUNT_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";
    private static final String CONTAINER = "sas-container";
    private static final String BLOB = "azblob://%s/%s/hello.txt"
            .formatted(ACCOUNT, CONTAINER);

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> AZURITE =
            new GenericContainer<>(
                    DockerImageName.parse(
                            "mcr.microsoft.com/azure-storage/azurite:latest"))
                                    .withCommand(
                                            "azurite-blob",
                                            "--blobHost", "0.0.0.0",
                                            "--blobPort", "10000",
                                            "--skipApiVersionCheck")
                                    .withExposedPorts(10000);

    private static String endpoint;
    private static String readOnlySas;

    @BeforeAll
    static void upload() {
        endpoint = "http://%s:%s/%s".formatted(
                AZURITE.getHost(), AZURITE.getFirstMappedPort(), ACCOUNT);
        BlobContainerClient container = new BlobContainerClientBuilder()
                .endpoint(endpoint)
                .containerName(CONTAINER)
                .credential(new StorageSharedKeyCredential(
                        ACCOUNT, ACCOUNT_KEY))
                .buildClient();
        container.create();
        container.getBlobClient("hello.txt")
                .upload(BinaryData.fromString("hello sas"), true);
        readOnlySas = container.generateSas(new BlobServiceSasSignatureValues(
                OffsetDateTime.now().plusHours(1),
                BlobContainerSasPermission.parse("rl")));
    }

    private static String read(AzureBlobFetcher fetcher) throws IOException {
        var path = fetcher.resolvePath(BLOB);
        try (var in = Files.newInputStream(path)) {
            return new String(in.readAllBytes(), UTF_8);
        }
    }

    @Test
    void sasTokenAsPasswordOfCredentialsWithoutUsernameIsUsed()
            throws IOException {
        var fetcher = new AzureBlobFetcher();
        fetcher.getConfiguration().setEndpoint(endpoint)
                .getCredentials().setPassword(readOnlySas);
        assertThat(read(fetcher)).isEqualTo("hello sas");
    }

    @Test
    void sasTokenStartingWithQuestionMarkIsAccepted() throws IOException {
        var fetcher = new AzureBlobFetcher();
        fetcher.getConfiguration().setEndpoint(endpoint)
                .getCredentials().setPassword("?" + readOnlySas);
        assertThat(read(fetcher)).isEqualTo("hello sas");
    }

    @Test
    void sasTokenInConfigurationIsStillUsed() throws IOException {
        var fetcher = new AzureBlobFetcher();
        fetcher.getConfiguration().setEndpoint(endpoint)
                .setSasToken(readOnlySas);
        assertThat(read(fetcher)).isEqualTo("hello sas");
    }

    @Test
    void anInvalidSasTokenInPasswordIsRefused() {
        var fetcher = new AzureBlobFetcher();
        fetcher.getConfiguration().setEndpoint(endpoint)
                .getCredentials().setPassword("sv=2020-01-01&sig=nope");
        assertThatThrownBy(() -> read(fetcher))
                .isInstanceOf(IOException.class);
    }
}
