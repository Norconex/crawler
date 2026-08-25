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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.SerializationException;

import com.hazelcast.collection.IQueue;
import com.hazelcast.core.HazelcastInstance;
import com.norconex.crawler.core.cluster.CacheQueue;
import com.norconex.crawler.core.cluster.ClusterException;
import com.norconex.crawler.core.util.SerialUtil;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HazelcastQueueAdapter<T> implements CacheQueue<T> {

    private static final long OFFER_TIMEOUT_MS = 15000;

    private final IQueue<Object> hzQueue;
    private final HazelcastInstance hzInstance;
    private final Class<T> valueType;
    private final String name;

    @SuppressWarnings("unchecked")
    public HazelcastQueueAdapter(
            IQueue<Object> hzQueue,
            HazelcastInstance hzInstance,
            Class<T> valueType) {
        this.hzQueue = Objects.requireNonNull(hzQueue, "queue");
        name = hzQueue.getName();
        this.hzInstance = hzInstance;
        this.valueType =
                valueType == null ? (Class<T>) String.class : valueType;
    }

    @Override
    public void add(T item) {
        if (!isQueueAvailable() || item == null) {
            return;
        }
        Object toStore;
        // If the value is already a String, store as-is. Otherwise
        // serialize to JSON for maximum JDBC portability.
        if (item instanceof String || valueType == String.class) {
            toStore = item;
        } else {
            try {
                toStore = SerialUtil.toJsonString(item);
            } catch (SerializationException e) {
                LOG.debug("Could not serialize queue item; storing "
                        + "toString: {}", e.toString());
                toStore = item.toString();
            }
        }
        // Offer to the distributed FIFO queue with a hard timeout so queue
        // store/network stalls do not block a crawler thread indefinitely.
        // The timeout check below is deliberately outside this try block:
        // ClusterException is a RuntimeException, so throwing it from inside
        // would have been immediately re-caught by "catch (Exception e)"
        // and re-wrapped with a generic message, losing the specific
        // "within N ms" detail.
        Boolean added;
        try {
            added = hzQueue.offer(
                    toStore,
                    OFFER_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClusterException(
                    "Interrupted while adding item to queue '%s'."
                            .formatted(hzQueue.getName()),
                    e);
        } catch (Exception e) {
            throw new ClusterException("Could not add item to queue '%s'."
                    .formatted(hzQueue.getName()),
                    e);
        }
        if (!Boolean.TRUE.equals(added)) {
            throw new ClusterException(
                    "Could not add item to queue '%s' within %d ms."
                            .formatted(hzQueue.getName(),
                                    OFFER_TIMEOUT_MS));
        }
    }

    @Override
    public T poll() {
        var list = pollBatch(1);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public List<T> pollBatch(int batchSize) {
        var batch = new ArrayList<T>(Math.max(1, batchSize));
        if (!isQueueAvailable()) {
            return batch;
        }

        for (var i = 0; i < batchSize; i++) {
            Object obj = null;
            try {
                obj = hzQueue.poll();
            } catch (Exception e) {
                LOG.debug("Could not poll item from queue '{}': {}",
                        hzQueue.getName(), e.toString());
            }
            if (obj == null) {
                break; // queue empty
            }

            T val = null;
            if (obj instanceof String str && valueType != String.class) {
                try {
                    val = SerialUtil.fromJson(str, valueType);
                } catch (Exception e) {
                    LOG.debug("Could not deserialize queue item: {}",
                            e.toString());
                    // fallback to returning the raw string
                    @SuppressWarnings("unchecked")
                    var cast = (T) str;
                    val = cast;
                }
            } else {
                @SuppressWarnings("unchecked")
                var cast = (T) obj;
                val = cast;
            }
            batch.add(val);
        }

        return batch;
    }

    @Override
    public int size() {
        if (!isQueueAvailable()) {
            return 0;
        }
        try {
            return hzQueue.size();
        } catch (Exception e) {
            LOG.debug("Could not get size for queue '{}': {}",
                    hzQueue.getName(), e.toString());
            return 0;
        }
    }

    @Override
    public boolean isEmpty() {
        if (!isQueueAvailable()) {
            return true;
        }
        try {
            return hzQueue.isEmpty();
        } catch (Exception e) {
            LOG.debug("Could not check emptiness for queue '{}': {}",
                    hzQueue.getName(), e.toString());
            return true;
        }
    }

    @Override
    public void clear() {
        if (!isQueueAvailable()) {
            return;
        }
        try {
            hzQueue.clear();
        } catch (Exception e) {
            LOG.debug("Could not clear queue '{}': {}",
                    hzQueue.getName(), e.toString());
        }
    }

    private boolean isQueueAvailable() {
        var lifecycle = hzInstance.getLifecycleService();
        if (!lifecycle.isRunning()) {
            var qname = hzQueue.getName();
            LOG.debug("Skipping operation on queue '{}' because "
                    + "Hazelcast instance is not running.", qname);
            return false;
        }
        return true;
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
