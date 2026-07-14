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
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import com.norconex.crawler.fs.fetch.impl.ReadOnlyPath;

/**
 * A path within an {@link AzureBlobFileSystem}.
 */
final class AzureBlobPath extends ReadOnlyPath {

    private final AzureBlobFileSystem fs;

    AzureBlobPath(AzureBlobFileSystem fs, String path) {
        this(fs, ReadOnlyPath.parse(path));
    }

    private AzureBlobPath(AzureBlobFileSystem fs, List<String> segments) {
        super(fs, List.copyOf(segments), "AzureBlobPath");
        this.fs = fs;
    }

    String path() {
        return normalizedPath();
    }

    String blobName() {
        return String.join("/", segments());
    }

    String blobPrefix() {
        var name = blobName();
        return name.isEmpty() || name.endsWith("/") ? name : name + "/";
    }

    @Override
    public AzureBlobFileSystem getFileSystem() {
        return fs;
    }

    @Override
    protected Path createPath(List<String> segments) {
        return new AzureBlobPath(fs, segments);
    }

    @Override
    public URI toUri() {
        try {
            return new URI(
                    fs.scheme(),
                    null,
                    fs.account(),
                    -1,
                    "/" + fs.container() + path(),
                    null,
                    null);
        } catch (URISyntaxException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }
}
