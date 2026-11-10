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
package com.norconex.crawler.core.cluster;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.norconex.crawler.core.cluster.pipeline.PipelineManager;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.session.CrawlerSession;

public interface Cluster extends Closeable {

    /**
     * Returns the number of nodes in the cluster.
     * @return node count
     */
    int getNodeCount();

    /**
     * Returns the names of all nodes in the cluster.
     * @return node names
     */
    List<String> getNodeNames();

    ClusterNode getLocalNode();

    CacheManager getCacheManager();

    PipelineManager getPipelineManager();

    /**
     * Returns the crawl session bound to this cluster node, or {@code null}
     * if the session has not been bound yet.
     * @return the crawl session
     */
    CrawlerSession getCrawlSession();

    /**
     * Binds the given crawl session to this cluster node. Called once during
     * session initialization.
     * @param session the crawl session
     */
    void bindSession(CrawlerSession session);

    void init(Path crawlerWorkDir, boolean isClustered,
            Map<String, Class<?>> cacheTypes);

    /**
     * Convenience overload for tests and simple scenarios that do not
     * need to pre-register cache value types.
     * @param crawlerWorkDir working directory
     * @param isClustered whether running in clustered mode
     */
    default void init(Path crawlerWorkDir, boolean isClustered) {
        init(crawlerWorkDir, isClustered, Map.of());
    }

    /**
     * Starts monitoring for stop signals. Should only be called by commands
     * that need to respond to stop requests (e.g., CrawlerCommand).
     */
    void startStopMonitoring();

    //TODO consider adding a "reason" argument that we would store.
    /**
     * Requests to stop the cluster. Does not wait.
     */
    void stop();

    /**
     * Stops the crawl pipelines running on this node, firing
     * {@link CrawlerEvent#CRAWLER_STOP_REQUEST_BEGIN} before and
     * {@link CrawlerEvent#CRAWLER_STOP_REQUEST_END} after. Every cluster
     * implementation must stop a node through this method, whatever the way
     * the stop reached the node, so listeners are told on every node and in
     * every cluster mode.
     */
    default void stopLocally() {
        var session = getCrawlSession();
        if (session != null) {
            session.markStopRequested();
            session.fire(CrawlerEvent.CRAWLER_STOP_REQUEST_BEGIN, session);
        }
        getPipelineManager().stop();
        if (session != null) {
            session.fire(CrawlerEvent.CRAWLER_STOP_REQUEST_END, session);
        }
    }

    //    /**
    //     * Checks if the cluster is configured in standalone (non-clustered)
    //     * mode.
    //     * <p>
    //     * Use this when you need to know the intended clustering mode from
    //     * configuration, not the current runtime state. For example, to avoid
    //     * waiting for cluster stabilization in standalone mode or other
    //     * node synchronization activities.
    //     * </p>
    //     * @return true if configured for standalone mode
    //     */
    //    boolean isStandalone();

    @Override
    void close();
}
