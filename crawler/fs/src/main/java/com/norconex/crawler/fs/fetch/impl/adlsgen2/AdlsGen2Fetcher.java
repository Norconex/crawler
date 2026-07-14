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
package com.norconex.crawler.fs.fetch.impl.adlsgen2;

import static com.norconex.crawler.fs.fetch.impl.FileFetchUtil.referenceStartsWith;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

import com.azure.storage.common.StorageSharedKeyCredential;
import com.azure.storage.file.datalake.DataLakeFileSystemClient;
import com.azure.storage.file.datalake.DataLakeFileSystemClientBuilder;
import com.norconex.commons.lang.encrypt.EncryptionUtil;
import com.norconex.crawler.core.fetch.FetchRequest;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.fs.doc.FsDocMetadata;
import com.norconex.crawler.fs.fetch.impl.AbstractNioFetcher;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Fetcher for Azure Data Lake Storage Gen2, using references such as
 * <code>abfss://filesystem@account.dfs.core.windows.net/path/to/file</code>.
 * </p>
 */
@Slf4j
@ToString
@EqualsAndHashCode
public class AdlsGen2Fetcher extends AbstractNioFetcher<AdlsGen2FetcherConfig> {

    @Getter
    private final AdlsGen2FetcherConfig configuration =
            new AdlsGen2FetcherConfig();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final AdlsGen2FileSystemProvider provider =
            new AdlsGen2FileSystemProvider();

    @Override
    protected void fetcherShutdown(CrawlerSession crawler) {
        provider.openFileSystems().forEach(fs -> {
            try {
                fs.close();
            } catch (RuntimeException e) {
                LOG.warn("Could not close ADLS Gen2 file system.", e);
            }
        });
        super.fetcherShutdown(crawler);
    }

    @Override
    protected boolean acceptRequest(@NonNull FetchRequest fetchRequest) {
        return referenceStartsWith(fetchRequest, "abfs://", "abfss://");
    }

    @Override
    protected Path resolvePath(String reference) throws IOException {
        var location = AdlsGen2Location.from(URI.create(reference));
        provider.setAclDisabled(configuration.isAclDisabled());
        var fs = provider.getOrCreateFileSystem(location, this::buildClient);
        return fs.getPath(location.path());
    }

    @Override
    protected void fetchMetadata(
            com.norconex.importer.doc.Doc doc, @NonNull Path path,
            @NonNull java.nio.file.attribute.BasicFileAttributes attrs)
            throws IOException {
        super.fetchMetadata(doc, path, attrs);
        if (configuration.isAclDisabled()) {
            return;
        }
        var adlsPath = (AdlsGen2Path) path;
        var aclMeta = provider.consumeAcl(adlsPath.path());
        if (aclMeta != null) {
            aclMeta.forEach((k, v) -> doc.getMetadata().addList(k, v));
            // keep compatibility with ACL-enabled fetchers that always expose
            // at least one ACL-root field when ACL extraction is enabled
            if (doc.getMetadata().getStrings(FsDocMetadata.ACL + ".owner")
                    .isEmpty()
                    && doc.getMetadata().getStrings(
                            FsDocMetadata.ACL + ".permissions").isEmpty()) {
                doc.getMetadata().set(FsDocMetadata.ACL + ".enabled", true);
            }
        }
    }

    DataLakeFileSystemClient buildClient(AdlsGen2Location location) {
        var cfg = configuration;
        var creds = cfg.getCredentials();
        var account = location.account();
        if (creds.isSet()
                && StringUtils.isNotBlank(creds.getUsername())
                && !StringUtils.equalsIgnoreCase(creds.getUsername(),
                        account)) {
            throw new IllegalArgumentException(
                    "ADLS Gen2 credentials username must match the "
                            + "reference account: " + account);
        }

        var builder = newClientBuilder()
                .endpoint(resolveEndpoint(location))
                .fileSystemName(location.fileSystem());

        if (StringUtils.isNotBlank(cfg.getSasToken())) {
            builder.sasToken(StringUtils.removeStart(cfg.getSasToken(), "?"));
        } else if (creds.isSet()) {
            builder.credential(new StorageSharedKeyCredential(
                    account,
                    EncryptionUtil.decryptPassword(creds)));
        }
        return builder.buildClient();
    }

    DataLakeFileSystemClientBuilder newClientBuilder() {
        return new DataLakeFileSystemClientBuilder();
    }

    private String resolveEndpoint(AdlsGen2Location location) {
        var endpoint = configuration.getEndpoint();
        if (StringUtils.isNotBlank(endpoint)) {
            return endpoint;
        }
        return "https://%s.dfs.core.windows.net"
                .formatted(location.account().toLowerCase(Locale.ROOT));
    }
}
