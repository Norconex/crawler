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
package com.norconex.crawler.fs.fetch.acl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.norconex.committer.core.acl.AclLevel;

/**
 * <p>
 * The {@code path} levels of a file on a source where reading a file also
 * needs the right to go through every folder above it (POSIX-style sources,
 * and Linux servers shared through Samba): one level per folder above that
 * does not let everybody through, in order from the top, without repeating
 * a level already there. Most files get none, or one.
 * </p>
 * <p>
 * Each folder's level is worked out once per crawl and kept, keyed by the
 * folder's path. A folder whose permissions could not be read is not kept,
 * so it is tried again for the next file.
 * </p>
 * @param <K> the key a folder is known by (its path)
 */
public final class TraverseLevels<K> {

    /** The name of the levels this gives. */
    public static final String LEVEL_NAME = "path";

    /**
     * Reads a folder's level for the right to go through it.
     * @param <K> the key a folder is known by
     */
    @FunctionalInterface
    public interface FolderReader<K> {
        /**
         * @param folder the folder
         * @return who may go through it, named {@value #LEVEL_NAME}
         * @throws IOException if its permissions could not be read
         */
        AclLevel read(K folder) throws IOException;
    }

    private final Map<K, Optional<AclLevel>> cache = new ConcurrentHashMap<>();

    /**
     * The levels for the folders above a file.
     * @param folders the folders above the file, in any order (the top one
     *                first gives the most natural order)
     * @param reader  how to read one folder
     * @return the levels, possibly none
     * @throws IOException if a folder's permissions could not be read
     */
    public List<AclLevel> levels(List<K> folders, FolderReader<K> reader)
            throws IOException {
        List<AclLevel> levels = new ArrayList<>();
        for (K folder : folders) {
            var level = cache.get(folder);
            if (level == null) {
                var read = reader.read(folder);
                level = PosixAcl.admitsEverybody(read)
                        ? Optional.empty()
                        : Optional.of(read);
                cache.put(folder, level);
            }
            level.filter(l -> !levels.contains(l)).ifPresent(levels::add);
        }
        return levels;
    }

    /** Forgets the folders read so far. */
    public void clear() {
        cache.clear();
    }
}
