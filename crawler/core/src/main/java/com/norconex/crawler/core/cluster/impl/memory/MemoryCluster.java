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
package com.norconex.crawler.core.cluster.impl.memory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.norconex.crawler.core.cluster.CacheManager;
import com.norconex.crawler.core.cluster.Cluster;
import com.norconex.crawler.core.cluster.ClusterNode;
import com.norconex.crawler.core.cluster.pipeline.PipelineManager;
import com.norconex.crawler.core.session.CrawlerSession;

import lombok.extern.slf4j.Slf4j;

/**
 * Lightweight single-node {@link Cluster} implementation that requires
 * no Hazelcast or any external infrastructure. Uses in-memory data
 * structures for caching and runs pipeline steps sequentially on the
 * calling thread.
 *
 * <p><b>No persistence:</b> all data is lost when the JVM exits.
 * For a file-backed single-node cluster that persists across restarts,
 * use the MVStore-based cluster instead.</p>
 */
@Slf4j
public class MemoryCluster implements Cluster {

    private static final ClusterNode LOCAL_NODE = new ClusterNode() {
        @Override
        public String getNodeName() {
            return "memory";
        }

        @Override
        public boolean isCoordinator() {
            return true;
        }
    };

    private final MemoryCacheManager cacheManager =
            new MemoryCacheManager();
    private LocalPipelineManager pipelineManager;
    private CrawlerSession session;
    private Path currentWorkDir;

    @Override
    public void init(
            Path crawlerWorkDir,
            boolean isClustered,
            Map<String, Class<?>> cacheTypes) {
        // When the workDir changes (e.g., a new Crawler reuses the same
        // connector but targets a different directory), all in-memory
        // caches must be cleared to avoid state leaking across crawlers
        // that expect independent storage.
        if (currentWorkDir != null
                && !currentWorkDir.equals(crawlerWorkDir)) {
            LOG.debug("Work directory changed from {} to {}; "
                    + "clearing all in-memory caches.",
                    currentWorkDir, crawlerWorkDir);
            cacheManager.clearCaches();
        }
        currentWorkDir = crawlerWorkDir;
        LOG.debug("In-memory cluster initialized (no persistence, "
                + "no external infrastructure).");
    }

    @Override
    public void bindSession(CrawlerSession session) {
        this.session = session;
        this.pipelineManager = session != null
                ? new LocalPipelineManager(session)
                : null;
    }

    @Override
    public int getNodeCount() {
        return 1;
    }

    @Override
    public List<String> getNodeNames() {
        return List.of(LOCAL_NODE.getNodeName());
    }

    @Override
    public ClusterNode getLocalNode() {
        return LOCAL_NODE;
    }

    @Override
    public CacheManager getCacheManager() {
        return cacheManager;
    }

    @Override
    public PipelineManager getPipelineManager() {
        return pipelineManager;
    }

    @Override
    public CrawlerSession getCrawlSession() {
        return session;
    }

    @Override
    public void startStopMonitoring() {
        // No-op: single node, nothing to monitor.
    }

    @Override
    public void stop() {
        if (pipelineManager != null) {
            stopLocally();
        }
    }

    @Override
    public void close() {
        // Clear ephemeral caches that would be lost on Hazelcast shutdown.
        // The persistent caches (crawlSession, crawler, ledger_*,
        // pipeline caches) must survive for incremental crawling.
        cacheManager.getCrawlRunCache().clear();
        cacheManager.getAdminCache().clear();
        LOG.debug("In-memory cluster closed.");
    }
}
