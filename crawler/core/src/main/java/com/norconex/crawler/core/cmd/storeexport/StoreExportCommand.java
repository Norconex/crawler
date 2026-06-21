/* Copyright 2024-2026 Norconex Inc.
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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.IOUtils;

import com.norconex.commons.lang.file.FileUtil;
import com.norconex.crawler.core.CrawlerException;
import com.norconex.crawler.core.cluster.ClusterException;
import com.norconex.crawler.core.cluster.SerializedCache;
import com.norconex.crawler.core.cmd.Command;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.core.util.SerialUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class StoreExportCommand implements Command {

    private final Path exportDir;
    private final boolean pretty;

    //TODO have wrapper StoppableRunnable that when invoked,
    // set stopRequested on context?  Or do it higher up on context?

    @Override
    public void execute(CrawlerSession session) {
        var ctx = session.getCrawlContext();

        Thread.currentThread().setName(ctx.getId() + "/STORE_EXPORT");

        // Export/Import are meant to run on a single node only. We ensure
        // this by checking if we are the coordinator.

        if (session.getCluster().getLocalNode().isCoordinator()) {
            session.fire(CrawlerEvent.CRAWLER_STORE_EXPORT_BEGIN, this);
            try {
                exportAllStores(session);
            } catch (Exception e) {
                throw new ClusterException(
                        "A problem occured while exporting crawler caches.", e);
            }
            session.fire(CrawlerEvent.CRAWLER_STORE_EXPORT_END, this);
        } else {
            LOG.warn("""
                Exporting can only be performed on a single node. \
                Another node started the export process so this one \
                will ignore the request.""");
        }
    }

    private void exportAllStores(CrawlerSession session)
            throws IOException {
        var cacheManager = session.getCluster().getCacheManager();
        Files.createDirectories(exportDir);

        var outFile = exportDir.resolve(
                FileUtil.toSafeFileName(session.getCrawlerId() + ".zip"));
        LOG.info("Exporting crawler storage to file: {}", outFile);

        try (var zipOS = new ZipOutputStream(
                IOUtils.buffer(Files.newOutputStream(outFile)), UTF_8)) {
            cacheManager.exportCaches(serialCache -> {
                var name = serialCache.getCacheName();
                //NOTE: export only persistent caches
                if (serialCache.isPersistent()) {
                    try {
                        zipOS.putNextEntry(new ZipEntry(
                                FileUtil.toSafeFileName(name) + ".json"));
                        exportOneStore(session, serialCache, zipOS);
                        zipOS.flush();
                        zipOS.closeEntry();
                    } catch (IOException e) {
                        throw new CrawlerException(
                                "Could not export store: " + name, e);
                    }
                }
            });
            zipOS.flush();
        }
        LOG.info("Storage exported to file: {}", outFile);
    }

    private void exportOneStore(
            CrawlerSession session,
            SerializedCache serialCache,
            OutputStream out) throws IOException {

        var name = serialCache.getCacheName();
        var writer = SerialUtil.jsonGenerator(out, pretty);
        var cnt = 0L;

        LOG.info("Exporting \"{}\" cache entries...", name);

        writer.writeStartObject();
        writer.writeStringProperty("crawler", session.getCrawlerId());
        writer.writeStringProperty("store", name);
        writer.writeStringProperty("type", serialCache.getClassName());
        writer.writeStringProperty("cacheType",
                serialCache.getCacheType().name());
        writer.writeName("records");
        writer.writeStartArray();

        for (var entry : serialCache) {
            try {
                writer.writeStartObject();
                writer.writeStringProperty("id", entry.getKey());
                writer.writePOJOProperty("object", entry.getJson());
                writer.writeEndObject();
                cnt++;
                if (cnt % 1000 == 0) {
                    LOG.info(" Exported {} \"{}\" records.",
                            NumberFormat.getNumberInstance().format(cnt), name);
                }
            } catch (RuntimeException e) {
                throw new CrawlerException(
                        "Could not export " + entry.getKey(), e);
            }
        }
        LOG.info(" Total exported: {} records.",
                NumberFormat.getNumberInstance().format(cnt));

        writer.writeEndArray();
        writer.writeEndObject();
        writer.flush();
    }
}
