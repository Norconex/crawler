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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;

/** How the client is authenticated: shared key, or a SAS token. */
@Timeout(30)
class AzureBlobFetcherTest {

    private static final AzureBlobLocation LOCATION = AzureBlobLocation.from(
            URI.create("azblob://account/container/path"));

    private final BlobContainerClientBuilder builder =
            mock(BlobContainerClientBuilder.class);
    private final BlobContainerClient client = mock(BlobContainerClient.class);

    private AzureBlobFetcher fetcher() {
        when(builder.endpoint(any())).thenReturn(builder);
        when(builder.containerName(any())).thenReturn(builder);
        when(builder.sasToken(any())).thenReturn(builder);
        when(builder.credential(any(StorageSharedKeyCredential.class)))
                .thenReturn(builder);
        when(builder.buildClient()).thenReturn(client);
        return new AzureBlobFetcher() {
            @Override
            BlobContainerClientBuilder newClientBuilder() {
                return builder;
            }
        };
    }

    @Test
    void sharedKeyCredentialsAreUsedWhenTheyHaveAUsername() {
        var f = fetcher();
        f.getConfiguration().getCredentials()
                .setUsername("account").setPassword("secret");

        assertThat(f.buildClient(LOCATION)).isSameAs(client);

        verify(builder).endpoint("https://account.blob.core.windows.net");
        verify(builder).containerName("container");
        verify(builder).credential(any(StorageSharedKeyCredential.class));
        verify(builder, never()).sasToken(any());
    }

    @Test
    void passwordOfCredentialsWithoutUsernameIsTheSasToken() {
        var f = fetcher();
        f.getConfiguration().getCredentials().setPassword("?sig=abc123");

        assertThat(f.buildClient(LOCATION)).isSameAs(client);

        verify(builder).sasToken("sig=abc123");
        verify(builder, never())
                .credential(any(StorageSharedKeyCredential.class));
    }

    @Test
    void sasTokenOfConfigurationIsUsedAndWinsOverCredentials() {
        var f = fetcher();
        f.getConfiguration().setSasToken("?sig=fromconfig");
        f.getConfiguration().getCredentials().setPassword("sig=frompassword");

        f.buildClient(LOCATION);

        verify(builder).sasToken("sig=fromconfig");
        verify(builder, never()).sasToken("sig=frompassword");
    }

    @Test
    void noCredentialsMeansAnonymousAccessAndACustomEndpointIsUsed() {
        var f = fetcher();
        f.getConfiguration().setEndpoint("http://localhost:10000/account");

        f.buildClient(LOCATION);

        verify(builder).endpoint("http://localhost:10000/account");
        verify(builder, never()).sasToken(any());
        verify(builder, never())
                .credential(any(StorageSharedKeyCredential.class));
    }

    @Test
    void aUsernameThatIsNotTheAccountOfTheReferenceIsRefused() {
        var f = fetcher();
        f.getConfiguration().getCredentials()
                .setUsername("otheraccount").setPassword("secret");

        assertThatThrownBy(() -> f.buildClient(LOCATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reference account");
    }
}
