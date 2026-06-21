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
package com.norconex.crawler.core.cmd.storeexport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.norconex.crawler.core.cluster.CacheManager;
import com.norconex.crawler.core.cluster.Cluster;
import com.norconex.crawler.core.cluster.ClusterException;
import com.norconex.crawler.core.cluster.ClusterNode;
import com.norconex.crawler.core.cluster.SerializedCache;
import com.norconex.crawler.core.cluster.SerializedCache.CacheType;
import com.norconex.crawler.core.cluster.SerializedCache.SerializedEntry;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.session.CrawlerSession;

@Timeout(30)
class StoreExportCommandTest {

    private static final String CRAWLER_ID = "test-crawler";

    @TempDir
    private Path tempDir;

    @Test
    void execute_asCoordinator_exportsPersistentCachesOnly() throws Exception {
        var cacheManager = mock(CacheManager.class);
        var session = session(true, cacheManager);

        var persistent = serializedCache("queue", true,
                new SerializedEntry("id1", "{\"a\":1}"),
                new SerializedEntry("id2", "{\"b\":2}"));
        var transientCache = serializedCache("runtime", false,
                new SerializedEntry("skip", "{\"c\":3}"));
        stubExport(cacheManager, persistent, transientCache);

        new StoreExportCommand(tempDir, false).execute(session);

        var zipEntries = readZip(tempDir.resolve(CRAWLER_ID + ".zip"));
        // Only the persistent cache is exported.
        assertThat(zipEntries).containsKey("queue.json");
        assertThat(zipEntries).doesNotContainKey("runtime.json");

        var json = zipEntries.get("queue.json");
        assertThat(json)
                .contains("\"crawler\":\"" + CRAWLER_ID + "\"")
                .contains("\"store\":\"queue\"")
                .contains("\"cacheType\":\"QUEUE\"")
                .contains("id1")
                .contains("id2");

        verify(session).fire(
                eq(CrawlerEvent.CRAWLER_STORE_EXPORT_BEGIN), any());
        verify(session).fire(
                eq(CrawlerEvent.CRAWLER_STORE_EXPORT_END), any());
    }

    @Test
    void execute_prettyOption_producesIndentedJson() throws Exception {
        var cacheManager = mock(CacheManager.class);
        var session = session(true, cacheManager);
        stubExport(cacheManager, serializedCache("queue", true,
                new SerializedEntry("id1", "{\"a\":1}")));

        new StoreExportCommand(tempDir, true).execute(session);

        var json = readZip(tempDir.resolve(CRAWLER_ID + ".zip"))
                .get("queue.json");
        // Pretty printing spans multiple lines; compact output would not.
        assertThat(json).contains("\n");
    }

    @Test
    void execute_notCoordinator_skipsExport() {
        var cacheManager = mock(CacheManager.class);
        var session = session(false, cacheManager);

        new StoreExportCommand(tempDir, false).execute(session);

        assertThat(tempDir.resolve(CRAWLER_ID + ".zip")).doesNotExist();
        verify(cacheManager, never()).exportCaches(any());
        verify(session, never()).fire(
                eq(CrawlerEvent.CRAWLER_STORE_EXPORT_BEGIN), any());
    }

    @Test
    void execute_exportFailure_wrappedInClusterException() {
        var cacheManager = mock(CacheManager.class);
        var session = session(true, cacheManager);
        doThrow(new RuntimeException("boom"))
                .when(cacheManager).exportCaches(any());

        assertThatThrownBy(
                () -> new StoreExportCommand(tempDir, false).execute(session))
                        .isInstanceOf(ClusterException.class)
                        .hasRootCauseMessage("boom");
    }

    private CrawlerSession session(
            boolean coordinator, CacheManager cacheManager) {
        var session = mock(CrawlerSession.class);
        var ctx = mock(CrawlerContext.class);
        var cluster = mock(Cluster.class);
        var node = mock(ClusterNode.class);
        when(session.getCrawlContext()).thenReturn(ctx);
        when(ctx.getId()).thenReturn(CRAWLER_ID);
        when(session.getCrawlerId()).thenReturn(CRAWLER_ID);
        when(session.getCluster()).thenReturn(cluster);
        when(cluster.getLocalNode()).thenReturn(node);
        when(node.isCoordinator()).thenReturn(coordinator);
        when(cluster.getCacheManager()).thenReturn(cacheManager);
        return session;
    }

    private static void stubExport(
            CacheManager cacheManager, SerializedCache... caches) {
        doAnswer(inv -> {
            Consumer<SerializedCache> consumer = inv.getArgument(0);
            for (var cache : caches) {
                consumer.accept(cache);
            }
            return null;
        }).when(cacheManager).exportCaches(any());
    }

    private static SerializedCache serializedCache(
            String name, boolean persistent, SerializedEntry... entries) {
        var cache = new SerializedCache();
        cache.setCacheName(name);
        cache.setClassName("com.example." + name);
        cache.setPersistent(persistent);
        cache.setCacheType(CacheType.QUEUE);
        cache.setEntries(List.of(entries).iterator());
        return cache;
    }

    private static Map<String, String> readZip(Path zipFile) throws Exception {
        Map<String, String> entries = new HashMap<>();
        try (var zis = new ZipInputStream(
                Files.newInputStream(zipFile), UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(),
                        new String(zis.readAllBytes(), UTF_8));
            }
        }
        return entries;
    }
}
