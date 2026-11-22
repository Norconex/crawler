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
package com.norconex.crawler.core.cluster.impl.memory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.norconex.crawler.core.cluster.CacheEntryUpdate;
import com.norconex.crawler.core.cluster.CacheMap;
import com.norconex.crawler.core.cluster.QueryFilter;

/**
 * In-memory {@link CacheMap} backed by a {@link ConcurrentHashMap}.
 * Not persistent. Queries via {@link QueryFilter} are evaluated using
 * field-level reflection.
 */
public class InMemoryCacheMap<T> implements CacheMap<T> {

    private final ConcurrentHashMap<String, T> store =
            new ConcurrentHashMap<>();
    private final String name;

    public InMemoryCacheMap(String name) {
        this.name = name;
    }

    @Override
    public boolean isEmpty() {
        return store.isEmpty();
    }

    @Override
    public long size() {
        return store.size();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isPersistent() {
        return false;
    }

    @Override
    public void put(String key, T value) {
        store.put(key, value);
    }

    @Override
    public void putAll(Map<String, T> entries) {
        store.putAll(entries);
    }

    @Override
    public Optional<T> get(String key) {
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public void remove(String key) {
        store.remove(key);
    }

    @Override
    public void clear() {
        store.clear();
    }

    @Override
    public boolean containsKey(String key) {
        return store.containsKey(key);
    }

    @Override
    public T getOrDefault(String key, T defaultValue) {
        return store.getOrDefault(key, defaultValue);
    }

    @Override
    public T computeIfAbsent(
            String key, Function<String, ? extends T> mappingFunction) {
        return store.computeIfAbsent(key, mappingFunction);
    }

    @Override
    public Optional<T> computeIfPresent(String key,
            BiFunction<String, ? super T, ? extends T> remappingFunction) {
        return Optional.ofNullable(
                store.computeIfPresent(key, remappingFunction));
    }

    @Override
    public Optional<T> compute(String key,
            BiFunction<String, ? super T, ? extends T> remappingFunction) {
        return Optional.ofNullable(store.compute(key, remappingFunction));
    }

    @Override
    public T merge(String key, T value,
            BiFunction<? super T, ? super T, ? extends T> remappingFunction) {
        return store.merge(key, value, remappingFunction);
    }

    @Override
    public T putIfAbsent(String key, T value) {
        return store.putIfAbsent(key, value);
    }

    @Override
    public boolean replace(String key, T oldValue, T newValue) {
        return store.replace(key, oldValue, newValue);
    }

    @Override
    public Optional<T> update(String key, CacheEntryUpdate<T> update) {
        Objects.requireNonNull(update, "update");
        var changed = new Object[1];
        store.compute(key, (k, current) -> {
            var next = update.apply(current);
            if (next == null) {
                return current;
            }
            changed[0] = next;
            return next;
        });
        @SuppressWarnings("unchecked")
        var next = (T) changed[0];
        return Optional.ofNullable(next);
    }

    @Override
    public List<T> query(QueryFilter filter) {
        return filterStream(filter).collect(Collectors.toList());
    }

    @Override
    public Iterator<T> queryIterator(QueryFilter filter) {
        return query(filter).iterator();
    }

    @Override
    public long count(QueryFilter filter) {
        return filterStream(filter).count();
    }

    @Override
    public void delete(QueryFilter filter) {
        if (filter == null || filter.getFieldName() == null) {
            store.clear();
            return;
        }
        store.entrySet().removeIf(e -> matches(e.getValue(), filter));
    }

    @Override
    public void forEach(BiConsumer<String, ? super T> action) {
        store.forEach(action);
    }

    @Override
    public List<String> keys() {
        return new ArrayList<>(store.keySet());
    }

    private Stream<T> filterStream(QueryFilter filter) {
        if (filter == null || filter.getFieldName() == null) {
            return store.values().stream();
        }
        return store.values().stream().filter(v -> matches(v, filter));
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
            field.setAccessible(true);
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
