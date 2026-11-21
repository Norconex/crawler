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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.AbstractMap.SimpleEntry;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hazelcast.map.IMap;
import com.norconex.commons.lang.event.EventManager;
import com.norconex.crawler.core.cluster.impl.hazelcast.HazelcastMapAdapter;
import com.norconex.crawler.core.session.CrawlerSession;

@Isolated
@ExtendWith(MockitoExtension.class)
class ClusterCounterIncrementTest {
    private static final String FAIL_ON_INDETERMINATE_STATE =
            "hazelcast.operation.fail.on.indeterminate.state";
    private static final String ATOMIC_COUNTERS =
            "norconex.cluster.atomicCounters";
    private static final String COUNTER = "DOCUMENT_PROCESSED";

    @Mock
    private IMap<String, Object> map;
    @Mock
    private IMap<String, Long> eventCountsMap;
    @Mock
    private HazelcastMapAdapter<Long> eventCounts;
    private String originalSetting;

    @BeforeEach
    void rememberSetting() {
        originalSetting = System.getProperty(FAIL_ON_INDETERMINATE_STATE);
    }

    @AfterEach
    void restoreSetting() {
        if (originalSetting == null) {
            System.clearProperty(FAIL_ON_INDETERMINATE_STATE);
        } else {
            System.setProperty(FAIL_ON_INDETERMINATE_STATE, originalSetting);
        }
    }

    @ParameterizedTest
    @MethodSource("counterValues")
    void incrementsMissingLiveAndPersistedValues(Object value, long expected) {
        var entry = new SimpleEntry<String, Object>(COUNTER, value);

        new ClusterCounterIncrement(2L).process(entry);

        assertThat(entry.getValue()).isEqualTo(expected);
    }

    private static Stream<Arguments> counterValues() {
        return Stream.of(Arguments.of(null, 2L), Arguments.of(4L, 6L),
                Arguments.of(5, 7L), Arguments.of("6", 8L));
    }

    @Test
    void rejectsAnUnsupportedCounterValue() {
        var entry = new SimpleEntry<String, Object>(COUNTER, true);

        assertThatThrownBy(() -> new ClusterCounterIncrement(2L).process(entry))
                .isInstanceOf(ClassCastException.class);
        assertThat(entry.getValue()).isEqualTo(true);
    }

    @Test
    void requiresFailOnIndeterminateStateBeforeCallingHazelcast() {
        System.clearProperty(FAIL_ON_INDETERMINATE_STATE);

        assertThatThrownBy(
                () -> ClusterCounterIncrement.increment(map, COUNTER, 2L))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("fail-on-indeterminate-state");
        verifyNoInteractions(map);
    }

    @Test
    void propagatesAnUncertainUpdateWithoutRetryingIt() {
        System.setProperty(FAIL_ON_INDETERMINATE_STATE, "true");
        var failure = new IllegalStateException("update outcome unknown");
        when(map.executeOnKey(eq(COUNTER), any(ClusterCounterIncrement.class)))
                .thenThrow(failure);

        assertThatThrownBy(
                () -> ClusterCounterIncrement.increment(map, COUNTER, 2L))
                        .isSameAs(failure);
        verify(map).executeOnKey(eq(COUNTER),
                any(ClusterCounterIncrement.class));
    }

    @Test
    void crawlerMetricsRoutesOptedInHazelcastUpdatesToAnEntryProcessor() {
        var savedOptIn = System.getProperty(ATOMIC_COUNTERS);
        System.setProperty(ATOMIC_COUNTERS, "true");
        System.setProperty(FAIL_ON_INDETERMINATE_STATE, "true");
        try {
            var session = mock(CrawlerSession.class, RETURNS_DEEP_STUBS);
            when(session.getCrawlContext().getEventManager())
                    .thenReturn(new EventManager());
            when(session.getCluster().getCacheManager()
                    .getCacheMap("crawlEventCounts", Long.class))
                            .thenReturn(eventCounts);
            when(eventCounts.vendor()).thenReturn(eventCountsMap);

            try (var metrics = new CrawlerMetricsImpl()) {
                metrics.init(session);
                metrics.incrementCounter(COUNTER, 2L);
            }

            verify(eventCountsMap).executeOnKey(eq(COUNTER), any());
        } finally {
            if (savedOptIn == null) {
                System.clearProperty(ATOMIC_COUNTERS);
            } else {
                System.setProperty(ATOMIC_COUNTERS, savedOptIn);
            }
        }
    }
}
