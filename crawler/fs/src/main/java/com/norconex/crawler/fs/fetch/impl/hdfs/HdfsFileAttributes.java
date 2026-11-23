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
package com.norconex.crawler.fs.fetch.impl.hdfs;

import java.nio.file.attribute.FileTime;

import com.norconex.crawler.fs.fetch.impl.ReadOnlyFileAttributes;

/**
 * Attributes of an HDFS resource, parsed from a WebHDFS
 * {@code GETFILESTATUS}/{@code LISTSTATUS} {@code FileStatus} JSON object.
 */
final class HdfsFileAttributes extends ReadOnlyFileAttributes {

    private final String owner;
    private final String group;
    private final String permission;
    private final boolean aclBit;

    HdfsFileAttributes(
            boolean directory, long size, long modificationTimeMillis,
            long accessTimeMillis) {
        this(directory, size, modificationTimeMillis, accessTimeMillis,
                null, null, null, false);
    }

    HdfsFileAttributes(
            boolean directory, long size, long modificationTimeMillis,
            long accessTimeMillis, String owner, String group,
            String permission, boolean aclBit) {
        super(
                directory,
                size,
                FileTime.fromMillis(modificationTimeMillis),
                FileTime.fromMillis(accessTimeMillis));
        this.owner = owner;
        this.group = group;
        this.permission = permission;
        this.aclBit = aclBit;
    }

    /** The owning user, or {@code null} if not given. */
    String owner() {
        return owner;
    }

    /** The owning group, or {@code null} if not given. */
    String group() {
        return group;
    }

    /** The mode, in octal ({@code 755}), or {@code null} if not given. */
    String permission() {
        return permission;
    }

    /** Whether the resource has an ACL beside its mode bits. */
    boolean aclBit() {
        return aclBit;
    }
}
