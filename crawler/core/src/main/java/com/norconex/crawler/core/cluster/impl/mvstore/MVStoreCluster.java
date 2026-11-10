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
package com.norconex.crawler.core.cluster.impl.mvstore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.norconex.crawler.core.cluster.CacheManager;
import com.norconex.crawler.core.cluster.Cluster;
import com.norconex.crawler.core.cluster.ClusterNode;
import com.norconex.crawler.core.cluster.impl.memory.LocalPipelineManager;
import com.norconex.crawler.core.cluster.pipeline.PipelineManager;
import com.norconex.crawler.core.session.CrawlerSession;

import lombok.extern.slf4j.Slf4j;

/**
 * File-backed single-node {@link Cluster} implementation using H2 MVStore.
 * Provides native file persistence with zero external infrastructure —
 * no database or Hazelcast needed.
 *
 * <p>This is the default cluster implementation for single-node crawling.
 * Data is stored in an MVStore file at {@code {workDir}/mvstore.db} and
 * persists across crawler restarts, enabling incremental crawling.</p>
 *
 * <p>Pipeline steps execute sequentially on the calling thread via
 * {@link LocalPipelineManager} (same as the in-memory
 * cluster).</p>
 */
@Slf4j
public class MVStoreCluster implements Cluster {

    private static final ClusterNode LOCAL_NODE = new ClusterNode() {
        @Override
        public String getNodeName() {
            return "mvstore";
        }

        @Override
        public boolean isCoordinator() {
            return true;
        }
    };

    private final MVStoreClusterConnectorConfig config;
    private final MVStoreCacheManager cacheManager =
            new MVStoreCacheManager();
    private LocalPipelineManager pipelineManager;
    private CrawlerSession session;
    private Path currentWorkDir;

    MVStoreCluster(MVStoreClusterConnectorConfig config) {
        this.config = config;
    }

    @Override
    public void init(
            Path crawlerWorkDir,
            boolean isClustered,
            Map<String, Class<?>> cacheTypes) {
        if (currentWorkDir != null
                && !currentWorkDir.equals(crawlerWorkDir)) {
            LOG.debug("Work directory changed from {} to {}; "
                    + "closing and re-opening MVStore.",
                    currentWorkDir, crawlerWorkDir);
            cacheManager.close();
        }
        currentWorkDir = crawlerWorkDir;
        cacheManager.open(crawlerWorkDir, config);
        LOG.debug("MVStore cluster initialized at: {}", crawlerWorkDir);
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
        // Clear ephemeral caches (same as MemoryCluster).
        cacheManager.getEphRunCache().clear();
        cacheManager.getEphAdminCache().clear();
        // Commit and close the MVStore file.
        cacheManager.close();
        LOG.debug("MVStore cluster closed.");
    }
}
