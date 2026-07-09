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

import static com.norconex.crawler.fs.fetch.impl.FileFetchUtil.referenceStartsWith;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.HttpStorageOptions;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.storage.contrib.nio.CloudStorageConfiguration;
import com.google.cloud.storage.contrib.nio.CloudStorageFileSystem;
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
 * Fetcher for Google Cloud Storage (<code>gs://bucket/object</code>),
 * backed by the official Google-maintained
 * <code>google-cloud-nio</code> provider.
 * </p>
 */
@Slf4j
@ToString
@EqualsAndHashCode
public class GcsFetcher extends AbstractNioFetcher<GcsFetcherConfig> {

    @Getter
    private final GcsFetcherConfig configuration = new GcsFetcherConfig();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final Map<String, CloudStorageFileSystem> openFileSystems =
            new ConcurrentHashMap<>();

    @Override
    protected void fetcherShutdown(CrawlerSession crawler) {
        openFileSystems.values().forEach(fs -> {
            try {
                fs.close();
            } catch (IOException e) {
                LOG.warn("Could not close GCS file system: {}", fs, e);
            }
        });
        openFileSystems.clear();
        super.fetcherShutdown(crawler);
    }

    @Override
    protected boolean acceptRequest(@NonNull FetchRequest fetchRequest) {
        return referenceStartsWith(fetchRequest, "gs://");
    }

    @Override
    protected Path resolvePath(String reference) throws IOException {
        var uri = URI.create(reference);
        var fs = openFileSystems.computeIfAbsent(
                uri.getHost(), this::openFileSystem);
        var path = uri.getPath();
        if (StringUtils.isNotBlank(path) && !"/".equals(path)) {
            path = StringUtils.removeStart(path, "/");
        }
        return fs.getPath(StringUtils.defaultIfBlank(path, "/"));
    }

    CloudStorageFileSystem openFileSystem(String bucket) {
        return CloudStorageFileSystem.forBucket(
                bucket,
                CloudStorageConfiguration.builder()
                        .usePseudoDirectories(true)
                        .build(),
                buildStorageOptions());
    }

    StorageOptions buildStorageOptions() {
        var endpoint = configuration.getEndpoint();
        if (StringUtils.isBlank(endpoint)) {
            return StorageOptions.getDefaultInstance();
        }
        return HttpStorageOptions.newBuilder()
                .setHost(endpoint)
                .setCredentials(NoCredentials.getInstance())
                .build();
    }
}
