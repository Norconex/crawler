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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import com.norconex.crawler.fs.fetch.impl.ReadOnlyPath;

/**
 * A path within an ADLS Gen2 file system.
 */
final class AdlsGen2Path extends ReadOnlyPath {

    private final AdlsGen2FileSystem fs;

    AdlsGen2Path(AdlsGen2FileSystem fs, String path) {
        this(fs, ReadOnlyPath.parse(path));
    }

    private AdlsGen2Path(AdlsGen2FileSystem fs, List<String> segments) {
        super(fs, List.copyOf(segments), "AdlsGen2Path");
        this.fs = fs;
    }

    String path() {
        return normalizedPath();
    }

    String pathName() {
        return String.join("/", segments());
    }

    @Override
    public AdlsGen2FileSystem getFileSystem() {
        return fs;
    }

    @Override
    protected Path createPath(List<String> segments) {
        return new AdlsGen2Path(fs, segments);
    }

    @Override
    public URI toUri() {
        try {
            return new URI(
                    fs.scheme(),
                    fs.fileSystemName(),
                    fs.host(),
                    -1,
                    path(),
                    null,
                    null);
        } catch (URISyntaxException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }
}
