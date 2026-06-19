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
package com.norconex.crawler.core.cmd.storeimport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.IOUtils;

import tools.jackson.databind.JsonNode;
import com.norconex.crawler.core.cluster.ClusterException;
import com.norconex.crawler.core.cluster.SerializedCache;
import com.norconex.crawler.core.cluster.SerializedCache.CacheType;
import com.norconex.crawler.core.cmd.Command;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.core.util.SerialUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class StoreImportCommand implements Command {

    private final Path inFile;

    @Override
    public void execute(CrawlerSession session) {
        var ctx = session.getCrawlContext();

        Thread.currentThread().setName(ctx.getId() + "/STORE_IMPORT");

        //Export/Import are meant to run on a single node only. We ensure
        // this by checking if we are the coordinator.
        if (session.getCluster().getLocalNode().isCoordinator()) {
            session.fire(CrawlerEvent.CRAWLER_STORE_IMPORT_BEGIN, this);
            try {
                importAllStores(session);
            } catch (Exception e) {
                throw new ClusterException("Could not import file: " + inFile,
                        e);
            }
            session.fire(CrawlerEvent.CRAWLER_STORE_IMPORT_END, this);
        } else {
            LOG.warn("""
                Importing can only be performed on a single node. \
                Another node started the export process so this one \
                will ignore the request.""");
        }

    }

    private void importAllStores(CrawlerSession session) throws IOException {
        List<SerializedCache> imports = new ArrayList<>();
        try (var zipIn = new ZipInputStream(
                IOUtils.buffer(Files.newInputStream(inFile)))) {
            while ((zipIn.getNextEntry()) != null) {
                importOneStore(zipIn, imports);
                zipIn.closeEntry();
            }
        }
        var cacheManager = session.getCluster().getCacheManager();
        cacheManager.importCaches(imports);
    }

    private void importOneStore(
            InputStream in,
            List<SerializedCache> imports) throws IOException {

        var rootNode = SerialUtil.getMapper().readTree(in);
        var cacheName = rootNode.get("store").asText();
        var className = rootNode.get("type").asText();

        LOG.info("Importing \"{}\" cache entries...", cacheName);

        var serializedCache = new SerializedCache();
        //NOTE we ignore the persistent field as it is the cache config/impl
        // that decides
        serializedCache.setCacheName(cacheName);
        serializedCache.setClassName(className);

        var cacheTypeField = rootNode.get("cacheType");
        serializedCache.setCacheType(
                cacheTypeField != null
                        ? CacheType.valueOf(cacheTypeField.asText())
                        : CacheType.MAP);

        var records = rootNode.get("records");
        if (records != null && records.isArray()) {
            serializedCache.setEntries(new Iterator<>() {
                private final Iterator<JsonNode> recordIterator =
                        records.iterator();

                @Override
                public boolean hasNext() {
                    return recordIterator.hasNext();
                }

                @Override
                public SerializedCache.SerializedEntry next() {
                    var rec = recordIterator.next();
                    try {
                        var idNode = rec.get("id");
                        String id = idNode.isNull() ? null : idNode.asText();
                        var object = rec.get("object").toString();
                        return new SerializedCache.SerializedEntry(id, object);
                    } catch (Exception e) {
                        throw new IllegalArgumentException(
                                "Could not import record for cache: "
                                        + cacheName,
                                e);
                    }
                }
            });
        } else {
            serializedCache.setEntries(new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public SerializedCache.SerializedEntry next() {
                    throw new NoSuchElementException("No entries available.");
                }
            });
        }

        imports.add(serializedCache);
        LOG.info("Imported \"{}\" cache entries successfully.", cacheName);
    }
}
