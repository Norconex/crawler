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
package com.norconex.crawler.fs.fetch.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.FileStore;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;

/**
 * Common read-only {@link FileSystemProvider} behavior shared by cloud and
 * remote filesystem providers.
 */
public abstract class ReadOnlyFileSystemProvider extends FileSystemProvider {

    protected abstract String fileSystemLabel();

    @Override
    public final SeekableByteChannel newByteChannel(
            Path path, Set<? extends OpenOption> options,
            FileAttribute<?>... attrs) throws IOException {
        byte[] content;
        try (var is = newInputStream(path)) {
            content = is.readAllBytes();
        }
        return new InMemoryReadOnlySeekableByteChannel(content);
    }

    @Override
    public final Map<String, Object> readAttributes(
            Path path, String attributes, LinkOption... options) {
        throw new UnsupportedOperationException(
                "Attribute-name based reads are not supported;"
                        + " use readAttributes(Path, Class).");
    }

    @Override
    public final <V extends FileAttributeView> V getFileAttributeView(
            Path path, Class<V> type, LinkOption... options) {
        if (type == BasicFileAttributeView.class) {
            return type.cast(new BasicFileAttributeView() {
                @Override
                public String name() {
                    return "basic";
                }

                @Override
                public BasicFileAttributes readAttributes()
                        throws IOException {
                    return ReadOnlyFileSystemProvider.this.readAttributes(
                            path, BasicFileAttributes.class);
                }

                @Override
                public void setTimes(
                        FileTime lastModifiedTime, FileTime lastAccessTime,
                        FileTime createTime) {
                    throw readOnlyException();
                }
            });
        }
        return null;
    }

    @Override
    public final void checkAccess(Path path, AccessMode... modes)
            throws IOException {
        readAttributes(path, BasicFileAttributes.class);
        for (var mode : modes) {
            if (mode == AccessMode.WRITE || mode == AccessMode.EXECUTE) {
                throw new AccessDeniedException(path.toString());
            }
        }
    }

    @Override
    public final boolean isSameFile(Path path, Path path2) {
        return path.equals(path2);
    }

    @Override
    public final boolean isHidden(Path path) {
        return false;
    }

    @Override
    public final FileStore getFileStore(Path path) {
        throw new UnsupportedOperationException(
                fileSystemLabel() + " file systems do not expose file stores.");
    }

    @Override
    public final void createDirectory(Path dir, FileAttribute<?>... attrs) {
        throw readOnlyException();
    }

    @Override
    public final void delete(Path path) {
        throw readOnlyException();
    }

    @Override
    public final void copy(Path source, Path target, CopyOption... options) {
        throw readOnlyException();
    }

    @Override
    public final void move(Path source, Path target, CopyOption... options) {
        throw readOnlyException();
    }

    @Override
    public final void setAttribute(
            Path path, String attribute, Object value,
            LinkOption... options) {
        throw readOnlyException();
    }

    private UnsupportedOperationException readOnlyException() {
        return new UnsupportedOperationException(
                fileSystemLabel() + " file systems are read-only.");
    }

    private static final class InMemoryReadOnlySeekableByteChannel
            implements SeekableByteChannel {

        private final ByteBuffer buffer;
        private boolean open = true;

        InMemoryReadOnlySeekableByteChannel(byte[] content) {
            this.buffer = ByteBuffer.wrap(content);
        }

        @Override
        public int read(ByteBuffer dst) {
            if (!buffer.hasRemaining()) {
                return -1;
            }
            var count = Math.min(dst.remaining(), buffer.remaining());
            var slice = buffer.slice();
            slice.limit(count);
            dst.put(slice);
            buffer.position(buffer.position() + count);
            return count;
        }

        @Override
        public int write(ByteBuffer src) {
            throw new NonWritableChannelException();
        }

        @Override
        public long position() {
            return buffer.position();
        }

        @Override
        public SeekableByteChannel position(long newPosition) {
            buffer.position((int) newPosition);
            return this;
        }

        @Override
        public long size() {
            return buffer.limit();
        }

        @Override
        public SeekableByteChannel truncate(long size) {
            throw new NonWritableChannelException();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
