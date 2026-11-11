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
package com.norconex.crawler.core.cluster.impl.hazelcast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.query.PagingPredicate;
import com.hazelcast.query.Predicate;
import com.hazelcast.query.Predicates;
import com.hazelcast.query.impl.predicates.PagingPredicateImpl;
import com.norconex.crawler.core.cluster.CacheMap;
import com.norconex.crawler.core.cluster.QueryFilter;
import com.norconex.crawler.core.util.SerialUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * Hazelcast IMap-based implementation of the Cache interface.
 * Adapter performs transparent conversion between values exposed as
 * type {@code T} and the values actually stored in Hazelcast which
 * may be plain {@link String} JSON (or raw String) or actual objects.
 *
 * This allows persisting everything as strings in the DB-backed
 * MapStore while presenting typed objects to callers.
 *
 * @param <T> the type of values stored in the cache view
 */
@Slf4j
public class HazelcastMapAdapter<T> implements CacheMap<T> {

    // underlying map kept as Object-valued to allow string storage
    private final IMap<String, Object> hzMap;
    private final HazelcastInstance hzInstance;
    private static final int DEFAULT_BATCH_SIZE = 100;
    private static final Set<String> CLOSED_LOGGED =
            ConcurrentHashMap.newKeySet();
    private final Class<T> type;
    private final String name;

    @SuppressWarnings("unchecked")
    public HazelcastMapAdapter(
            IMap<String, ?> hzMap,
            HazelcastInstance hzInstance,
            Class<T> type) {
        // cast is safe: we only treat values as Object and convert
        this.hzMap = (IMap<String, Object>) hzMap;
        name = hzMap.getName();
        this.hzInstance = hzInstance;
        this.type = type;
    }

    @Override
    public boolean isEmpty() {
        return supplyIfCache(hzMap::isEmpty, false);
    }

    @Override
    public void put(String key, T value) {
        runIfCache(() -> hzMap.put(key, toStored(value)));
    }

    @Override
    public void putAll(Map<String, T> entries) {
        runIfCache(() -> {
            var converted = new java.util.HashMap<String, Object>();
            entries.forEach((k, v) -> converted.put(k, toStored(v)));
            hzMap.putAll(converted);
        });
    }

    @Override
    public Optional<T> get(String key) {
        return Optional.ofNullable(
                supplyIfCache(() -> fromStored(hzMap.get(key)), null));
    }

    @Override
    public void remove(String key) {
        runIfCache(() -> hzMap.remove(key));
    }

    @Override
    public void clear() {
        runIfCache(hzMap::clear);
    }

    @Override
    public T computeIfAbsent(String key,
            Function<String, ? extends T> mappingFunction) {
        return supplyIfCache(() -> {
            var stored = hzMap.get(key);
            if (stored != null) {
                return fromStored(stored);
            }
            T newVal = mappingFunction.apply(key);
            var prev = hzMap.putIfAbsent(key, toStored(newVal));
            return prev != null ? fromStored(prev) : newVal;
        }, null);
    }

    @Override
    public Optional<T> computeIfPresent(String key,
            BiFunction<String, ? super T, ? extends T> remappingFunction) {
        return Optional.ofNullable(supplyIfCache(() -> {
            var storedOld = hzMap.get(key);
            if (storedOld != null) {
                var oldValue = fromStored(storedOld);
                T newValue = remappingFunction.apply(key, oldValue);
                if (newValue != null) {
                    hzMap.put(key, toStored(newValue));
                    return newValue;
                }
                hzMap.remove(key);
            }
            return null;
        }, null));
    }

    @Override
    public Optional<T> compute(String key,
            BiFunction<String, ? super T, ? extends T> remappingFunction) {
        return Optional.ofNullable(supplyIfCache(() -> {
            var storedOld = hzMap.get(key);
            var oldValue = fromStored(storedOld);
            T newValue = remappingFunction.apply(key, oldValue);
            if (newValue != null) {
                hzMap.put(key, toStored(newValue));
                return newValue;
            }
            if (oldValue != null) {
                hzMap.remove(key);
            }
            return null;
        }, null));
    }

    @Override
    public T merge(String key, T value,
            BiFunction<? super T, ? super T, ? extends T> remappingFunction) {
        return supplyIfCache(() -> {
            var storedOld = hzMap.get(key);
            var oldValue = fromStored(storedOld);
            var newValue = (oldValue == null) ? value
                    : remappingFunction.apply(oldValue, value);
            if (newValue != null) {
                hzMap.put(key, toStored(newValue));
            } else {
                hzMap.remove(key);
            }
            return newValue;
        }, null);
    }

    @Override
    public boolean containsKey(String key) {
        return supplyIfCache(() -> hzMap.containsKey(key), false);
    }

    @Override
    public T getOrDefault(String key, T defaultValue) {
        return supplyIfCache(
                () -> {
                    var stored = hzMap.getOrDefault(key, null);
                    var val = fromStored(stored);
                    return val != null ? val : defaultValue;
                }, defaultValue);
    }

    @Override
    public T putIfAbsent(String key, T value) {
        return supplyIfCache(() -> {
            var prev = hzMap.putIfAbsent(key, toStored(value));
            return fromStored(prev);
        }, null);
    }

    @Override
    public boolean replace(String key, T oldValue, T newValue) {
        return supplyIfCache(
                () -> hzMap.replace(key, toStored(oldValue),
                        toStored(newValue)),
                false);
    }

    @Override
    public List<T> query(QueryFilter filter) {
        return this.<List<T>>supplyIfCache(() -> {
            var objs = hzMap.values(toPredicate(filter));
            var out = new ArrayList<T>(objs.size());
            for (var o : objs) {
                out.add(fromStored(o));
            }
            return out;
        }, Collections.emptyList());
    }

    @Override
    public Iterator<T> queryIterator(QueryFilter filter) {
        return supplyIfCache(() -> new PagingIterator<>(
                hzMap,
                toPredicate(filter),
                DEFAULT_BATCH_SIZE), Collections.emptyIterator());
    }

    @Override
    public long count(QueryFilter filter) {
        return hzMap.keySet(toPredicate(filter)).size();
    }

    @Override
    public long size() {
        return supplyIfCache(hzMap::size, -1);
    }

    @Override
    public void delete(QueryFilter filter) {
        if (filter == null) {
            return;
        }
        runIfCache(() -> {
            LOG.debug("Deleting entries matching filter: {}...", filter);
            hzMap.removeAll(toPredicate(filter));
            LOG.debug("Finished deleting {} entries");
        });
    }

    @Override
    public void forEach(BiConsumer<String, ? super T> action) {
        runIfCache(() -> {
            for (var entry : hzMap.entrySet()) {
                action.accept(entry.getKey(), fromStored(entry.getValue()));
            }
        });
    }

    @Override
    public List<String> keys() {
        return supplyIfCache(
                () -> new ArrayList<>(hzMap.keySet()),
                new ArrayList<>());
    }

    @Override
    public void loadAll() {
        runIfCache(() -> {
            LOG.debug("Loading all entries for map '{}'...", name);
            hzMap.loadAll(false);
            LOG.debug("Loaded all entries for map '{}'.", name);
        });
    }

    /**
     * Return the underlying Hazelcast IMap as typed view.
     * Use with caution: underlying values are stored as Objects/Strings.
     * @return hazelcast IMap
     */
    @SuppressWarnings("unchecked")
    public IMap<String, T> vendor() {
        return (IMap<String, T>) hzMap;
    }

    //--- Private methods ------------------------------------------------------

    private Predicate<String, Object> toPredicate(QueryFilter filter) {
        if (filter == null || StringUtils.isBlank(filter.getFieldName())) {
            return Predicates.alwaysTrue();
        }
        return Predicates.equal(
                filter.getFieldName(),
                Objects.toString(filter.getFieldValue(), ""));
    }

    private <R> R supplyIfCache(Supplier<R> supplier, R defaultValue) {
        return isCacheClosed() ? defaultValue : supplier.get();
    }

    private void runIfCache(Runnable runnable) {
        if (!isCacheClosed()) {
            runnable.run();
        }
    }

    private boolean isCacheClosed() {
        var lifecycle = hzInstance.getLifecycleService();
        if (!lifecycle.isRunning()) {
            var mapName = hzMap.getName();
            if (CLOSED_LOGGED.add(mapName)) {
                LOG.warn("Attempted to use cache '{}' after it was closed.",
                        mapName);
            } else if (LOG.isDebugEnabled()) {
                LOG.debug("(suppressed) Attempted to use cache '{}' after it "
                        + "was closed.", mapName);
            }
            return true;
        }
        return false;
    }

    private Object toStored(T value) {
        // If the cache is typed as String, keep strings as-is. For all
        // other types, store the actual object so Hazelcast keeps typed
        // objects in memory. If older entries are strings (JSON), the
        // adapter will deserialize them on read in fromStored().
        return value;
    }

    private T fromStored(Object stored) {
        if (stored == null) {
            return null;
        }
        if (type == String.class) {
            return type.cast(Objects.toString(stored, null));
        }
        if (stored instanceof String s) {
            return SerialUtil.fromJson(s, type);
        }
        return type.cast(stored);
    }

    /**
     * Iterator that pages results from Hazelcast IMap using PagingPredicate.
     * Converts stored values to type T lazily.
     */
    private static class PagingIterator<T> implements Iterator<T> {
        private final IMap<String, Object> map;
        private final com.hazelcast.query.Predicate<String, Object> predicate;
        private final int pageSize;
        private int pageIndex = 0;
        private Iterator<T> currentPageIterator;
        private boolean lastPage = false;

        PagingIterator(IMap<String, Object> map,
                com.hazelcast.query.Predicate<String, Object> predicate,
                int pageSize) {
            this.map = map;
            this.predicate = predicate;
            this.pageSize = pageSize;
            loadPage();
        }

        @SuppressWarnings("unchecked")
        private void loadPage() {
            PagingPredicate<String, Object> pagingPredicate =
                    new PagingPredicateImpl<>(predicate, pageSize);
            pagingPredicate.setPage(pageIndex);
            Iterable<Entry<String, Object>> entrySet =
                    map.entrySet(pagingPredicate);
            List<T> values = new ArrayList<>();
            for (var entry : entrySet) {
                var o = entry.getValue();
                // best-effort conversion: if value is String try to keep it,
                // otherwise cast (the adapter will handle typed conversion).
                values.add((T) o);
            }
            currentPageIterator = values.iterator();
            lastPage = values.size() < pageSize;
        }

        @Override
        public boolean hasNext() {
            if (currentPageIterator == null) {
                return false;
            }
            if (currentPageIterator.hasNext()) {
                return true;
            }
            if (!lastPage) {
                pageIndex++;
                loadPage();
                return hasNext();
            }
            return false;
        }

        @Override
        public T next() {
            if (!hasNext()) {
                throw new java.util.NoSuchElementException();
            }
            return currentPageIterator.next();
        }
    }

    @Override
    public boolean isPersistent() {
        return HazelcastUtil.isPersistent(hzInstance, getName());
    }

    @Override
    public String getName() {
        return name;
    }
}
