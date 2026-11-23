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
package com.norconex.crawler.core.cluster.impl.hazelcast;

import java.util.Map;

import com.hazelcast.map.EntryProcessor;
import com.norconex.crawler.core.cluster.CacheEntryUpdate;

/**
 * Applies a {@link CacheEntryUpdate} on the member owning the entry, under
 * the partition's lock. Values stored as JSON text (older entries, or
 * entries loaded back from a store as text) are read like objects. Hazelcast
 * applies the same processor to the backup copy, which is why updates must
 * be deterministic.
 *
 * @param <T> the cache value type
 */
final class CacheEntryUpdateProcessor<T>
        implements EntryProcessor<String, Object, Object> {

    private static final long serialVersionUID = 1L;

    private final Class<T> type;
    private final CacheEntryUpdate<T> update;

    CacheEntryUpdateProcessor(Class<T> type, CacheEntryUpdate<T> update) {
        this.type = type;
        this.update = update;
    }

    @Override
    public Object process(Map.Entry<String, Object> entry) {
        var current = HazelcastMapAdapter.fromStored(entry.getValue(), type);
        var next = update.apply(current);
        if (next == null) {
            return null;
        }
        entry.setValue(next);
        return next;
    }
}
