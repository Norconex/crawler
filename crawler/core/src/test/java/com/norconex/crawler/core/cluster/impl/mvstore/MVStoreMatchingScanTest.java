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
package com.norconex.crawler.core.cluster.impl.mvstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.SerializationException;
import org.h2.mvstore.Cursor;
import org.h2.mvstore.DataUtils;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.crawler.core.cluster.QueryFilter;
import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.ledger.ProcessingStatus;

class MVStoreMatchingScanTest {

    private static final QueryFilter PROCESSED = QueryFilter.of(
            CrawlerEntry.Fields.processingStatus,
            ProcessingStatus.PROCESSED.name());

    @TempDir
    private Path tempDir;

    @Test
    void visitsOnlyMatchingEntriesInQueryOrder() {
        try (var store = openStore()) {
            var ledger = ledger(store);
            var visited = new ArrayList<CrawlerEntry>();
            ledger.forEachMatching(PROCESSED, visited::add);
            assertThat(visited).isEmpty();

            var first = entry("a", ProcessingStatus.PROCESSED);
            var last = entry("c", ProcessingStatus.PROCESSED);
            ledger.put("c", last);
            ledger.put("b", entry("b", ProcessingStatus.QUEUED));
            ledger.put("a", first);

            ledger.forEachMatching(PROCESSED, visited::add);

            assertThat(visited).containsExactlyElementsOf(ledger.query(PROCESSED));
            assertThat(visited).extracting(CrawlerEntry::getReference)
                    .containsExactly("a", "c");
        }
    }

    @Test
    void deliversFirstMatchBeforeReadingMalformedLaterEntry() {
        try (var store = openStore()) {
            var ledger = ledger(store);
            ledger.put("a", entry("a", ProcessingStatus.PROCESSED));
            store.<String, String>openMap("ledger").put("b", "{");
            var visited = new ArrayList<String>();

            assertThatThrownBy(() -> ledger.forEachMatching(
                    PROCESSED, entry -> visited.add(entry.getReference())))
                    .isInstanceOf(SerializationException.class);

            assertThat(visited).containsExactly("a");
        }
    }

    @Test
    void resumesAfterStorageFailureWithoutRedeliveringTheLastKey() {
        try (var store = openStore()) {
            var stored = store.<String, String>openMap("ledger");
            stored.put("a", "first");
            stored.put("b", "second");
            Cursor<String, String> firstCursor = mock();
            when(firstCursor.hasNext()).thenReturn(true).thenThrow(
                    new MVStoreException(DataUtils.ERROR_CHUNK_NOT_FOUND,
                            "injected cursor read failure"));
            when(firstCursor.next()).thenReturn("a");
            when(firstCursor.getValue()).thenReturn("first");
            MVMap<String, String> map = mock();
            when(map.getStore()).thenReturn(store);
            when(map.cursor(null)).thenReturn(firstCursor);
            when(map.cursor("a")).thenAnswer(call -> stored.cursor("a"));

            var cache = new MVStoreCacheMap<>(map, String.class, "ledger",
                    true);
            var visited = new ArrayList<String>();
            cache.forEachMatching(null, visited::add);

            assertThat(visited).containsExactly("first", "second");
            verify(map).cursor("a");
        }
    }

    @Test
    void propagatesConsumerFailureAfterOneDelivery() {
        try (var store = openStore()) {
            var ledger = ledger(store);
            ledger.put("a", entry("a", ProcessingStatus.PROCESSED));
            ledger.put("b", entry("b", ProcessingStatus.PROCESSED));
            var failure = new MVStoreException(
                    DataUtils.ERROR_CHUNK_NOT_FOUND, "consumer failed");
            var visited = new ArrayList<String>();

            assertThatThrownBy(() -> ledger.forEachMatching(PROCESSED, entry -> {
                visited.add(entry.getReference());
                throw failure;
            })).isSameAs(failure);

            assertThat(visited).containsExactly("a");
            var rescanned = new ArrayList<String>();
            ledger.forEachMatching(
                    PROCESSED, entry -> rescanned.add(entry.getReference()));
            assertThat(rescanned).isEqualTo(List.of("a", "b"));
        }
    }

    private MVStore openStore() {
        return new MVStore.Builder()
                .fileName(tempDir.resolve("mvstore.db").toString())
                .open();
    }

    private MVStoreCacheMap<CrawlerEntry> ledger(MVStore store) {
        return new MVStoreCacheMap<>(
                store.openMap("ledger"), CrawlerEntry.class, "ledger", true);
    }

    private CrawlerEntry entry(String reference, ProcessingStatus status) {
        var entry = new CrawlerEntry(reference);
        entry.setProcessingStatus(status);
        return entry;
    }
}
