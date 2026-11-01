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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import com.hazelcast.core.HazelcastInstance;

@Timeout(30)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SuppressWarnings("null")
class HazelcastQueueAndCounterIT {

    private HazelcastInstance hz;
    private HazelcastCacheManager cacheManager;
    private String prefix;

    @BeforeAll
    void startHazelcast() {
        hz = HazelcastTestSupport.startNode();
        cacheManager = new HazelcastCacheManager(hz);
    }

    @AfterAll
    void stopHazelcast() {
        cacheManager.close();
    }

    @BeforeEach
    void freshPrefix() {
        prefix = "test-" + UUID.randomUUID() + "-";
    }

    // -----------------------------------------------------------------
    // Queue: add and poll
    // -----------------------------------------------------------------

    @Test
    void testStringQueueAddPoll() {
        var queue = cacheManager.getCacheQueue(prefix + "q", String.class);
        queue.add("first");
        queue.add("second");
        assertThat(queue.poll()).isEqualTo("first");
        assertThat(queue.poll()).isEqualTo("second");
        assertThat(queue.poll()).isNull();
    }

    @Test
    void testQueuePollBatch() {
        var queue = cacheManager.getCacheQueue(prefix + "qbatch", String.class);
        queue.add("a");
        queue.add("b");
        queue.add("c");
        var batch = queue.pollBatch(2);
        assertThat(batch).containsExactly("a", "b");
        assertThat(queue.poll()).isEqualTo("c");
    }

    @Test
    void testQueueSize() {
        var queue = cacheManager.getCacheQueue(prefix + "qsize", String.class);
        assertThat(queue.size()).isZero();
        queue.add("x");
        assertThat(queue.size()).isEqualTo(1);
        queue.add("y");
        assertThat(queue.size()).isEqualTo(2);
        queue.poll();
        assertThat(queue.size()).isEqualTo(1);
    }

    @Test
    void testQueueClear() {
        var queue = cacheManager.getCacheQueue(prefix + "qclear", String.class);
        queue.add("item1");
        queue.add("item2");
        queue.clear();
        assertThat(queue.size()).isZero();
        assertThat(queue.poll()).isNull();
    }

    @Test
    void testQueueAddNull_isIgnored() {
        var queue = cacheManager.getCacheQueue(prefix + "qnull", String.class);
        // null items should be silently dropped
        queue.add(null);
        assertThat(queue.size()).isZero();
    }

    @Test
    void testQueueAddNonStringType() {
        var queue = cacheManager.getCacheQueue(
                prefix + "qobj", Integer.class);
        queue.add(42);
        var polled = queue.poll();
        assertThat(polled).isEqualTo(42);
    }

    // -----------------------------------------------------------------
    // Counter
    // -----------------------------------------------------------------

    @Test
    void testCounterIncrementAndGet() {
        var counterMap = hz.<String, Long>getMap(prefix + "ctr");
        var counter = new HazelcastCounter(counterMap, "c1");
        assertThat(counter.get()).isZero();
        assertThat(counter.incrementAndGet()).isEqualTo(1L);
        assertThat(counter.incrementAndGet()).isEqualTo(2L);
        assertThat(counter.get()).isEqualTo(2L);
    }

    @Test
    void testCounterDecrementAndGet() {
        var counterMap = hz.<String, Long>getMap(prefix + "ctrdec");
        var counter = new HazelcastCounter(counterMap, "c2");
        counter.set(10L);
        assertThat(counter.decrementAndGet()).isEqualTo(9L);
        assertThat(counter.getAndDecrement()).isEqualTo(9L);
        assertThat(counter.get()).isEqualTo(8L);
    }

    @Test
    void testCounterAddAndGet() {
        var counterMap = hz.<String, Long>getMap(prefix + "ctradd");
        var counter = new HazelcastCounter(counterMap, "c3");
        counter.set(5L);
        assertThat(counter.addAndGet(3L)).isEqualTo(8L);
        assertThat(counter.getAndAdd(2L)).isEqualTo(8L);
        assertThat(counter.get()).isEqualTo(10L);
    }

    @Test
    void testCounterReset() {
        var counterMap = hz.<String, Long>getMap(prefix + "ctrreset");
        var counter = new HazelcastCounter(counterMap, "c4");
        counter.set(100L);
        counter.reset();
        assertThat(counter.get()).isZero();
    }

    // -----------------------------------------------------------------
    // Set adapter
    // -----------------------------------------------------------------

    @Test
    void testSetAdapterContainsAndRemove() {
        var set = new HazelcastSetAdapter(hz.getSet(prefix + "set1"));
        assertThat(set.isEmpty()).isTrue();
        set.add("alpha");
        set.add("beta");
        assertThat(set.contains("alpha")).isTrue();
        assertThat(set.size()).isEqualTo(2);
        set.remove("alpha");
        assertThat(set.contains("alpha")).isFalse();
        set.clear();
        assertThat(set.isEmpty()).isTrue();
    }
}
