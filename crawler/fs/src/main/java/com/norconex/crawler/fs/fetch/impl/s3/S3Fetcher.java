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

import static com.norconex.crawler.fs.fetch.impl.FileFetchUtil.referenceStartsWith;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.encrypt.EncryptionUtil;
import com.norconex.crawler.core.fetch.FetchRequest;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.fs.fetch.impl.AbstractNioFetcher;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * <p>
 * Fetcher for Amazon S3 (<code>s3://bucket/key</code>), backed by a
 * custom, read-only NIO.2 {@link java.nio.file.spi.FileSystemProvider}
 * ({@link S3FileSystemProvider}) built directly on the standard AWS SDK
 * v2 {@link S3Client} - not the AWS Common Runtime (CRT) client, avoiding
 * its ~19MB native-library dependency.
 * </p>
 * <p>
 * S3 has no real directories: a reference like
 * <code>s3://bucket/folder/</code> is treated as a directory whenever any
 * object exists with that key as a prefix, matching how the AWS console
 * and CLI simulate folders.
 * </p>
 */
@Slf4j
@ToString
@EqualsAndHashCode
public class S3Fetcher extends AbstractNioFetcher<S3FetcherConfig> {

    @Getter
    private final S3FetcherConfig configuration = new S3FetcherConfig();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final S3FileSystemProvider provider = new S3FileSystemProvider();

    @Override
    protected void fetcherShutdown(CrawlerSession crawler) {
        provider.openFileSystems().forEach(fs -> {
            try {
                fs.close();
            } catch (RuntimeException e) {
                LOG.warn("Could not close S3 file system.", e);
            }
        });
        super.fetcherShutdown(crawler);
    }

    @Override
    protected boolean acceptRequest(@NonNull FetchRequest fetchRequest) {
        return referenceStartsWith(fetchRequest, "s3://");
    }

    @Override
    protected Path resolvePath(String reference) throws IOException {
        var uri = URI.create(reference);
        var fs = provider.getOrCreateFileSystem(uri, this::buildS3Client);
        return fs.getPath(StringUtils.defaultIfBlank(uri.getPath(), "/"));
    }

    // Builds an S3Client for the given bucket. Called once per bucket by
    // the provider. Package-private so config wiring can be exercised in
    // tests without a live server.
    S3Client buildS3Client(String bucket) {
        var cfg = configuration;
        var builder = S3Client.builder()
                .credentialsProvider(buildCredentialsProvider());

        if (StringUtils.isNotBlank(cfg.getRegion())) {
            builder.region(Region.of(cfg.getRegion()));
        }
        if (StringUtils.isNotBlank(cfg.getEndpoint())) {
            builder.endpointOverride(URI.create(cfg.getEndpoint()));
        }
        builder.forcePathStyle(cfg.isForcePathStyle());

        return builder.build();
    }

    private AwsCredentialsProvider buildCredentialsProvider() {
        var creds = configuration.getCredentials();
        if (!creds.isSet()) {
            return DefaultCredentialsProvider.create();
        }
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                        creds.getUsername(),
                        EncryptionUtil.decryptPassword(creds)));
    }
}
