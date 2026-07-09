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

import static com.norconex.crawler.fs.fetch.impl.FileFetchUtil.referenceStartsWith;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.norconex.commons.lang.encrypt.EncryptionUtil;
import com.norconex.crawler.core.fetch.FetchRequest;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.fs.fetch.impl.AbstractNioFetcher;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Fetcher for Azure Blob Storage, using references such as
 * <code>azblob://account/container/path/to/blob</code>.
 * </p>
 * <p>
 * Azure Blob Storage has no real directories. This fetcher models folders
 * from blob prefixes using the same delimiter-based listing approach as the
 * Azure portal and SDK.
 * </p>
 */
@Slf4j
@ToString
@EqualsAndHashCode
public class AzureBlobFetcher
        extends AbstractNioFetcher<AzureBlobFetcherConfig> {

    @Getter
    private final AzureBlobFetcherConfig configuration =
            new AzureBlobFetcherConfig();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final AzureBlobFileSystemProvider provider =
            new AzureBlobFileSystemProvider();

    @Override
    protected void fetcherShutdown(CrawlerSession crawler) {
        provider.openFileSystems().forEach(fs -> {
            try {
                fs.close();
            } catch (RuntimeException e) {
                LOG.warn("Could not close Azure Blob file system.", e);
            }
        });
        super.fetcherShutdown(crawler);
    }

    @Override
    protected boolean acceptRequest(@NonNull FetchRequest fetchRequest) {
        return referenceStartsWith(fetchRequest, "azblob://", "azureblob://");
    }

    @Override
    protected java.nio.file.Path resolvePath(String reference)
            throws IOException {
        var location = AzureBlobLocation.from(URI.create(reference));
        var fs = provider.getOrCreateFileSystem(location, this::buildClient);
        return fs.getPath(location.path());
    }

    BlobContainerClient buildClient(AzureBlobLocation location) {
        var cfg = configuration;
        var creds = cfg.getCredentials();
        var account = location.account();
        if (creds.isSet()
                && StringUtils.isNotBlank(creds.getUsername())
                && !StringUtils.equalsIgnoreCase(creds.getUsername(),
                        account)) {
            throw new IllegalArgumentException(
                    "Azure Blob credentials username must match the "
                            + "reference account: " + account);
        }

        var builder = new BlobContainerClientBuilder()
                .endpoint(resolveEndpoint(location))
                .containerName(location.container());

        if (StringUtils.isNotBlank(cfg.getSasToken())) {
            builder.sasToken(StringUtils.removeStart(cfg.getSasToken(), "?"));
        } else if (creds.isSet()) {
            builder.credential(new StorageSharedKeyCredential(
                    account,
                    EncryptionUtil.decryptPassword(creds)));
        }
        return builder.buildClient();
    }

    private String resolveEndpoint(AzureBlobLocation location) {
        var endpoint = configuration.getEndpoint();
        if (StringUtils.isNotBlank(endpoint)) {
            return endpoint;
        }
        return "https://%s.blob.core.windows.net"
                .formatted(location.account().toLowerCase(Locale.ROOT));
    }
}
