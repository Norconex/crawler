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
package com.norconex.crawler.core.cluster.impl.mvstore;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import org.h2.mvstore.DataUtils;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStoreException;

import com.norconex.crawler.core.cluster.CacheMap;
import com.norconex.crawler.core.cluster.QueryFilter;
import com.norconex.crawler.core.util.SerialUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * File-backed {@link CacheMap} implementation using H2 MVStore.
 * Values are stored as JSON strings in the underlying {@link MVMap}
 * for type safety and debuggability. All compound operations that
 * require atomicity are synchronized (safe for single-JVM use).
 *
 * <p>Queries via {@link QueryFilter} are evaluated using field-level
 * reflection, same approach as the in-memory implementation.</p>
 *
 * <p>{@link #count(QueryFilter)} on a field is answered from a counter index
 * built on first use (one scan) and then maintained on every write, so
 * frequent status counts do not scan the whole map. Full scans (queries,
 * {@code forEach}, index building) resume from the last key seen when
 * MVStore reports a chunk freed under the cursor
 * ({@code ERROR_CHUNK_NOT_FOUND}) while concurrent writers and background
 * compaction rewrite the file.</p>
 *
 * @param <T> the value type
 */
@Slf4j
public class MVStoreCacheMap<T> implements CacheMap<T> {

    private static final int MAX_READ_ATTEMPTS = 5;
    private static final long RETRY_PAUSE_MS = 25;
    // Index key standing for a null field value (map keys can't be null).
    private static final String NULL_KEY = "\u0000null";

    private final MVMap<String, String> map;
    private final Class<T> valueType;
    private final String name;
    private final boolean persistent;

    // fieldName -> (stringified field value -> number of entries)
    private final Map<String, Map<String, LongAdder>> indexes =
            new ConcurrentHashMap<>();
    // Writers hold the read lock (they can run concurrently) while an index
    // is built under the write lock so the initial scan is consistent.
    private final ReadWriteLock indexLock = new ReentrantReadWriteLock();

    public MVStoreCacheMap(
            MVMap<String, String> map,
            Class<T> valueType,
            String name,
            boolean persistent) {
        this.map = map;
        this.valueType = valueType;
        this.name = name;
        this.persistent = persistent;
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public long size() {
        return map.sizeAsLong();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isPersistent() {
        return persistent;
    }

    @Override
    public void put(String key, T value) {
        doPut(key, serialize(value));
    }

    @Override
    public void putAll(Map<String, T> entries) {
        entries.forEach((k, v) -> doPut(k, serialize(v)));
    }

    @Override
    public Optional<T> get(String key) {
        return Optional.ofNullable(deserialize(read(key)));
    }

    @Override
    public void remove(String key) {
        doRemove(key);
    }

    @Override
    public void clear() {
        indexLock.writeLock().lock();
        try {
            map.clear();
            indexes.values().forEach(Map::clear);
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    @Override
    public boolean containsKey(String key) {
        return read(key) != null;
    }

    @Override
    public T getOrDefault(String key, T defaultValue) {
        var json = read(key);
        return json != null ? deserialize(json) : defaultValue;
    }

    @Override
    public synchronized T computeIfAbsent(
            String key,
            Function<String, ? extends T> mappingFunction) {
        var json = read(key);
        if (json != null) {
            return deserialize(json);
        }
        var newValue = mappingFunction.apply(key);
        if (newValue != null) {
            doPut(key, serialize(newValue));
        }
        return newValue;
    }

    @Override
    public synchronized Optional<T> computeIfPresent(
            String key,
            BiFunction<String, ? super T, ? extends T> remappingFunction) {
        var json = read(key);
        if (json == null) {
            return Optional.empty();
        }
        var oldValue = deserialize(json);
        var newValue = remappingFunction.apply(key, oldValue);
        if (newValue != null) {
            doPut(key, serialize(newValue));
        } else {
            doRemove(key);
        }
        return Optional.ofNullable(newValue);
    }

    @Override
    public synchronized Optional<T> compute(
            String key,
            BiFunction<String, ? super T, ? extends T> remappingFunction) {
        var json = read(key);
        var oldValue = json != null ? deserialize(json) : null;
        var newValue = remappingFunction.apply(key, oldValue);
        if (newValue != null) {
            doPut(key, serialize(newValue));
        } else if (json != null) {
            doRemove(key);
        }
        return Optional.ofNullable(newValue);
    }

    @Override
    public synchronized T merge(
            String key, T value,
            BiFunction<? super T, ? super T, ? extends T> remappingFunction) {
        var json = read(key);
        var oldValue = json != null ? deserialize(json) : null;
        var newValue = oldValue == null
                ? value
                : remappingFunction.apply(oldValue, value);
        if (newValue != null) {
            doPut(key, serialize(newValue));
        } else {
            doRemove(key);
        }
        return newValue;
    }

    @Override
    public synchronized T putIfAbsent(String key, T value) {
        var json = read(key);
        if (json != null) {
            return deserialize(json);
        }
        doPut(key, serialize(value));
        return null;
    }

    @Override
    public synchronized boolean replace(String key, T oldValue, T newValue) {
        var json = read(key);
        if (json == null) {
            return false;
        }
        var current = deserialize(json);
        if (Objects.equals(current, oldValue)) {
            doPut(key, serialize(newValue));
            return true;
        }
        return false;
    }

    @Override
    public List<T> query(QueryFilter filter) {
        var matching = new ArrayList<T>();
        scan((k, json) -> {
            var value = deserialize(json);
            if (filter == null || filter.getFieldName() == null
                    || matches(value, filter)) {
                matching.add(value);
            }
        });
        return matching;
    }

    @Override
    public Iterator<T> queryIterator(QueryFilter filter) {
        return query(filter).iterator();
    }

    @Override
    public long count(QueryFilter filter) {
        if (filter == null || filter.getFieldName() == null) {
            return map.sizeAsLong();
        }
        var counts = indexes.get(filter.getFieldName());
        if (counts == null) {
            counts = buildIndex(filter.getFieldName());
        }
        var adder = counts.get(Objects.toString(
                filter.getFieldValue(), NULL_KEY));
        return adder == null ? 0 : Math.max(0, adder.sum());
    }

    @Override
    public void delete(QueryFilter filter) {
        if (filter == null || filter.getFieldName() == null) {
            clear();
            return;
        }
        // Collect keys to remove first to avoid ConcurrentModification
        var keysToRemove = new ArrayList<String>();
        scan((k, json) -> {
            if (matches(deserialize(json), filter)) {
                keysToRemove.add(k);
            }
        });
        keysToRemove.forEach(this::doRemove);
    }

    @Override
    public void forEach(BiConsumer<String, ? super T> action) {
        scan((k, json) -> action.accept(k, deserialize(json)));
    }

    @Override
    public List<String> keys() {
        var keys = new ArrayList<String>();
        scan((k, json) -> keys.add(k));
        return keys;
    }

    //--- Writes (keep the count indexes in step) ---

    private void doPut(String key, String json) {
        indexLock.readLock().lock();
        try {
            // put() returns the previous value atomically, so concurrent
            // writers on one key produce consistent counter deltas.
            updateIndexes(map.put(key, json), json);
        } finally {
            indexLock.readLock().unlock();
        }
    }

    private void doRemove(String key) {
        indexLock.readLock().lock();
        try {
            updateIndexes(map.remove(key), null);
        } finally {
            indexLock.readLock().unlock();
        }
    }

    private void updateIndexes(String oldJson, String newJson) {
        if (indexes.isEmpty() || Objects.equals(oldJson, newJson)) {
            return;
        }
        var oldValue = deserialize(oldJson);
        var newValue = deserialize(newJson);
        indexes.forEach((field, counts) -> {
            var oldKey = indexKey(oldValue, field);
            var newKey = indexKey(newValue, field);
            if (Objects.equals(oldKey, newKey)) {
                return;
            }
            if (oldKey != null) {
                counts.computeIfAbsent(oldKey, k -> new LongAdder())
                        .decrement();
            }
            if (newKey != null) {
                counts.computeIfAbsent(newKey, k -> new LongAdder())
                        .increment();
            }
        });
    }

    private Map<String, LongAdder> buildIndex(String fieldName) {
        indexLock.writeLock().lock();
        try {
            var existing = indexes.get(fieldName);
            if (existing != null) {
                return existing;
            }
            var counts = new ConcurrentHashMap<String, LongAdder>();
            scan((k, json) -> {
                var key = indexKey(deserialize(json), fieldName);
                if (key != null) {
                    counts.computeIfAbsent(key, x -> new LongAdder())
                            .increment();
                }
            });
            indexes.put(fieldName, counts);
            return counts;
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    // null when the value has no such field (it can never match a filter)
    private String indexKey(T value, String fieldName) {
        if (value == null) {
            return null;
        }
        try {
            var field = findField(value.getClass(), fieldName);
            if (field == null) {
                return null;
            }
            field.setAccessible(true); //NOSONAR
            return Objects.toString(field.get(value), NULL_KEY);
        } catch (Exception e) {
            return null;
        }
    }

    //--- Reads that survive chunks freed under a cursor ---

    private String read(String key) {
        return retryRead(() -> map.get(key));
    }

    /**
     * Visits all entries in key order. The store version is pinned for the
     * duration of the scan so background compaction cannot free (and the
     * file reuse) chunks the cursor still has to read. As a safety net, if
     * MVStore still reports a chunk as not found, scanning resumes with a
     * fresh cursor from the last key visited instead of failing.
     */
    private void scan(BiConsumer<String, String> visitor) {
        String last = null;
        var failures = 0;
        while (true) {
            var store = map.getStore();
            var pinned = store.registerVersionUsage();
            try {
                var cursor = map.cursor(last);
                while (cursor.hasNext()) {
                    var key = cursor.next();
                    if (key.equals(last)) {
                        continue;
                    }
                    visitor.accept(key, cursor.getValue());
                    last = key;
                }
                return;
            } catch (MVStoreException e) {
                failures = onChunkNotFound(e, failures);
            } finally {
                store.deregisterVersionUsage(pinned);
            }
        }
    }

    private <R> R retryRead(Supplier<R> reader) {
        var failures = 0;
        while (true) {
            try {
                return reader.get();
            } catch (MVStoreException e) {
                failures = onChunkNotFound(e, failures);
            }
        }
    }

    // returns the new failure count, or rethrows if not retryable
    private int onChunkNotFound(MVStoreException e, int failures) {
        if (e.getErrorCode() != DataUtils.ERROR_CHUNK_NOT_FOUND
                || failures + 1 >= MAX_READ_ATTEMPTS) {
            throw e;
        }
        LOG.warn("MVStore chunk not found reading map '{}' (attempt {} of "
                + "{}); retrying with a fresh cursor: {}",
                name, failures + 1, MAX_READ_ATTEMPTS, e.getMessage());
        try {
            Thread.sleep(RETRY_PAUSE_MS * (failures + 1));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw e;
        }
        return failures + 1;
    }

    private String serialize(T value) {
        if (value instanceof String str) {
            return str;
        }
        return SerialUtil.toJsonString(value);
    }

    @SuppressWarnings("unchecked")
    private T deserialize(String json) {
        if (json == null) {
            return null;
        }
        if (valueType == String.class) {
            return (T) json;
        }
        return SerialUtil.fromJson(json, valueType);
    }

    private boolean matches(T value, QueryFilter filter) {
        if (value == null || filter == null
                || filter.getFieldName() == null) {
            return true;
        }
        try {
            var field = findField(value.getClass(), filter.getFieldName());
            if (field == null) {
                return false;
            }
            field.setAccessible(true); //NOSONAR
            var fieldValue = field.get(value);
            return Objects.equals(
                    Objects.toString(fieldValue, null),
                    Objects.toString(filter.getFieldValue(), null));
        } catch (Exception e) {
            return false;
        }
    }

    private static Field findField(Class<?> clazz, String fieldName) {
        for (var c = clazz; c != null && c != Object.class;
                c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                // continue up the hierarchy
            }
        }
        return null;
    }
}
