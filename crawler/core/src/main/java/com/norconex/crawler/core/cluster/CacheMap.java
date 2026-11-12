/* Copyright 2025-2026 Norconex Inc.
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

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

public interface CacheMap<T> {

    //TODO add listener

    boolean isEmpty();

    void put(String key, T value);

    void putAll(Map<String, T> entries);

    Optional<T> get(String key);

    void remove(String key);

    void clear();

    T computeIfAbsent(String key,
            Function<String, ? extends T> mappingFunction);

    Optional<T> computeIfPresent(String key,
            BiFunction<String, ? super T,
                    ? extends T> remappingFunction);

    Optional<T> compute(String key,
            BiFunction<String, ? super T,
                    ? extends T> remappingFunction);

    T merge(String key, T value,
            BiFunction<? super T, ? super T,
                    ? extends T> remappingFunction);

    boolean containsKey(String key);

    T getOrDefault(String key, T defaultValue);

    /**
     * If the specified key is not already associated with a value, associates
     * it with the given value. This is equivalent to, for this cache:
     * <pre>
     * if (!cache.containsKey(key))
     *     return map.put(key, value);
     * else
     *     return map.get(key);
     *</pre>
     * except that the action is performed atomically.
     * @param key key with which the specified value is to be associated
     * @param value  value to be associated with the specified key
     * @return the previous value associated with the specified key, or
     *     {@code null} if there was no mapping for the key.
     */
    T putIfAbsent(String key, T value);

    boolean replace(String key, T oldValue, T newValue);

    /**
     * Queries the cache for entries matching the given query filter.
     * Note: This method loads all results into memory at once.
     * For large result sets, prefer {@link #forEachMatching}.
     *
     * @param filter the query filter
     * @return a list of matching entries or an empty list (never null)
     */
    List<T> query(QueryFilter filter);

    /**
     * Queries the cache for entries matching the given filter
     * and returns an iterator. Whether results are loaded eagerly depends on
     * the cache implementation.
     *
     * @param filter the query filter
     * @return an iterator over the matching entries
     */
    Iterator<T> queryIterator(QueryFilter filter);

    /**
     * Visits matching entries synchronously. Implementations may override this
     * method to stream results while keeping traversal resources scoped to the
     * call. The consumer should not mutate the cache during traversal.
     *
     * @param filter the query filter
     * @param action the action to perform for each matching entry
     */
    default void forEachMatching(
            QueryFilter filter, Consumer<? super T> action) {
        Objects.requireNonNull(action, "action");
        queryIterator(filter).forEachRemaining(action);
    }

    /**
     * Queries the cache for entries matching the given query filter
     * with pagination support.
     *
     * @param filter the query filter
     * @param startOffset the starting offset (0-based)
     * @param maxResults the maximum number of results to return
     * @return a list of matching entries within the specified range
     */
    /*
    List<T> queryPaged(QueryFilter filter, int startOffset, int maxResults);
    */
    /**
     * Queries the cache and processes results in a streaming fashion
     * without loading all results into memory at once.
     *
     * @param filter the query filter
     * @param consumer the consumer that will process each entry
     * @param batchSize the number of entries to process in each batch
     */
    /*
    void queryStream(QueryFilter filter, Consumer<T> consumer,
        int batchSize);
    */

    /**
     * Counts the number of entries matching the given query filter.
     * @param filter the query filter
     * @return the count of matching entries
     */
    long count(QueryFilter filter);

    /**
     * Counts the number of entries in this cache.
     * @return the number of entries
     */
    long size();

    /**
     * Deletes entries matching the given query filter.
     * @param filter the query filter
     */
    void delete(QueryFilter filter);

    void forEach(BiConsumer<String, ? super T> action);

    /**
     * Returns all keys in this cache. For distributed caches, this includes
     * keys from all nodes in the cluster, not just locally-owned keys.
     * @return a list of all keys in the cache
     */
    List<String> keys();

    /**
     * Eagerly loads all entries from the backing store (e.g., JDBC) into
     * this cache. For caches configured with {@code LAZY} initial load mode,
     * this ensures sub­sequent {@link #size()}, {@link #forEach}, and
     * {@link #keys()} calls return correct results. If the cache already
     * has its data loaded (e.g., {@code EAGER} mode), this is a no-op.
     */
    default void loadAll() {
        // no-op by default — implementations override when backed by a store
    }

    /**
     * Returns whether this cache persists data across restarts.
     * @return true if the cache is persistent, false if ephemeral
     */
    boolean isPersistent();

    /**
     * Gets the name of this cache.
     * @return cache name
     */
    String getName();

}
