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
package com.norconex.crawler.fs.fetch.impl.smb;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

/**
 * Attributes of an SMB resource. Built either from a directory listing
 * (where jcifs-ng already populates these from the enumeration response,
 * at no extra cost) or from a dedicated stat call.
 */
final class SmbFileAttributes implements BasicFileAttributes {

    private final boolean directory;
    private final long size;
    private final FileTime lastModifiedTime;

    SmbFileAttributes(boolean directory, long size, long lastModifiedMillis) {
        this.directory = directory;
        this.size = size;
        lastModifiedTime = FileTime.fromMillis(lastModifiedMillis);
    }

    @Override
    public FileTime lastModifiedTime() {
        return lastModifiedTime;
    }

    @Override
    public FileTime lastAccessTime() {
        return lastModifiedTime;
    }

    @Override
    public FileTime creationTime() {
        return lastModifiedTime;
    }

    @Override
    public boolean isRegularFile() {
        return !directory;
    }

    @Override
    public boolean isDirectory() {
        return directory;
    }

    @Override
    public boolean isSymbolicLink() {
        return false;
    }

    @Override
    public boolean isOther() {
        return false;
    }

    @Override
    public long size() {
        return Math.max(size, 0);
    }

    @Override
    public Object fileKey() {
        return null;
    }
}
