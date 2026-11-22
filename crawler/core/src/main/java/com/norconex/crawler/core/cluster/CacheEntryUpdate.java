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
package com.norconex.crawler.core.cluster;

import java.io.Serializable;

/**
 * A change to one cache entry, made atomically by
 * {@link CacheMap#update(String, CacheEntryUpdate)} and
 * {@link CacheMap#updateAll(java.util.Collection, CacheEntryUpdate)}.
 * <p>
 * Given the entry's current value, or <code>null</code> when the key has
 * none, it returns the value to store, or <code>null</code> to leave the
 * entry as it is. Deciding on the current value rather than comparing it
 * with an expected one keeps the change atomic whatever form a cache stores
 * its values in (objects, serialized bytes, JSON).
 * </p>
 * <p>
 * A clustered cache runs the update where the entry lives, possibly on
 * another node, and may run it again on a backup copy of the entry. An
 * implementation must therefore be serializable, deterministic and free of
 * side effects, and must not modify the value it is given (return a copy).
 * Prefer a named class to a lambda: serialized lambdas tie the nodes to the
 * exact same build of the class declaring them.
 * </p>
 *
 * @param <T> the cache value type
 */
@FunctionalInterface
public interface CacheEntryUpdate<T> extends Serializable {

    /**
     * Computes the new value of an entry.
     * @param current the current value, or <code>null</code> if none
     * @return the value to store, or <code>null</code> to leave it unchanged
     */
    T apply(T current);
}
