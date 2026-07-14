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

import java.nio.file.attribute.FileTime;

import com.norconex.crawler.fs.fetch.impl.ReadOnlyFileAttributes;

/**
 * Attributes of an S3 object or "virtual directory" (a key prefix; S3 has
 * no real directories).
 */
final class S3FileAttributes extends ReadOnlyFileAttributes {

    S3FileAttributes(boolean directory, long size, FileTime lastModifiedTime) {
        super(directory, size, lastModifiedTime);
    }
}
