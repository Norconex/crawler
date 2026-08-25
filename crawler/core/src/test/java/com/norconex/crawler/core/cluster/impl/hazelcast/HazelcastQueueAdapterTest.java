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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.hazelcast.collection.IQueue;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.LifecycleService;
import com.norconex.crawler.core.cluster.ClusterException;

@Timeout(30)
class HazelcastQueueAdapterTest {

    // Regression test for a bug where the ClusterException message was built
    // as ("... '%s' " + "within %d ms.").formatted(name, timeoutMs): since
    // .formatted() binds only to the second string literal (not the
    // concatenated result), the queue name (a String) was passed as the
    // argument for that literal's lone %d placeholder, throwing
    // IllegalFormatConversionException instead of the intended
    // ClusterException whenever a queue offer actually timed out.
    @SuppressWarnings("unchecked")
    @Test
    void add_offerTimesOut_throwsClusterExceptionWithFormattedMessage()
            throws InterruptedException {
        var hzQueue = mock(IQueue.class);
        when(hzQueue.getName()).thenReturn("my-queue");
        when(hzQueue.offer(any(), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);

        var hzInstance = mock(HazelcastInstance.class);
        var lifecycle = mock(LifecycleService.class);
        when(lifecycle.isRunning()).thenReturn(true);
        when(hzInstance.getLifecycleService()).thenReturn(lifecycle);

        var adapter = new HazelcastQueueAdapter<>(
                hzQueue, hzInstance, String.class);

        assertThatExceptionOfType(ClusterException.class)
                .isThrownBy(() -> adapter.add("item"))
                .satisfies(e -> assertThat(e.getMessage())
                        .contains("my-queue")
                        .contains("15000")
                        .doesNotContain("%s")
                        .doesNotContain("%d"));
    }
}
