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
package com.norconex.crawler.core.metrics;

import java.io.Serializable;
import java.util.Map;

import org.slf4j.LoggerFactory;

import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.IMap;
import com.norconex.crawler.core.util.SerialUtil;

/** Increments a clustered counter while the partition owns the entry. */
public final class ClusterCounterIncrement
        implements EntryProcessor<String, Object, Void>, Serializable {
    private static final long serialVersionUID = 1L;
    private final long delta;

    public ClusterCounterIncrement(long delta) {
        this.delta = delta;
    }

    @SuppressWarnings("unchecked")
    public static void increment(IMap<String, ?> map, String key, long delta) {
        try {
            if (!Boolean.getBoolean(
                    "hazelcast.operation.fail.on.indeterminate.state")) {
                throw new IllegalStateException(
                        "Cluster counters require fail-on-indeterminate-state");
            }
            // Never retry a write with an uncertain outcome in application code.
            ((IMap<String, Object>) map)
                    .executeOnKey(key, new ClusterCounterIncrement(delta));
        } catch (RuntimeException e) {
            // Preserve the failed update in the log for callers that handle
            // metrics exceptions without stopping the crawl.
            LoggerFactory.getLogger(ClusterCounterIncrement.class)
                    .error("CLUSTER_COUNTER_UPDATE_FAILED key={}; run is invalid",
                            key, e);
            throw e;
        }
    }

    @Override
    public Void process(Map.Entry<String, Object> entry) {
        Object value = entry.getValue();
        // JdbcMapStore can reload JSON strings; live entries are normally Longs.
        if (value instanceof String json) {
            value = SerialUtil.fromJson(json, Long.class);
        }
        long current;
        if (value == null) {
            current = 0;
        } else if (value instanceof Long number) {
            current = number;
        } else if (value instanceof Integer number) {
            current = number.longValue();
        } else {
            throw new ClassCastException(
                    "Unsupported counter value: " + value.getClass());
        }
        entry.setValue(current + delta);
        return null;
    }

    // Hazelcast's default backup processor applies this same delta to the backup.
    // It must handle a missing backup entry; no remote calls or I/O run here.
}
