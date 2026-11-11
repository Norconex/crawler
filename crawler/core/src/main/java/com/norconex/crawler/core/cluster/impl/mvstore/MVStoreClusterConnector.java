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

import com.norconex.commons.lang.config.Configurable;
import com.norconex.crawler.core.cluster.Cluster;
import com.norconex.crawler.core.cluster.ClusterConnector;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

/**
 * Connector that creates a file-backed {@link MVStoreCluster} using
 * H2's MVStore engine. This is the default connector for single-node
 * crawling, providing native file persistence with zero external
 * infrastructure.
 *
 * <p>The cluster instance is cached so that multiple crawler sessions
 * using the same connector share the same MVStore file.</p>
 */
@EqualsAndHashCode
@Getter
public class MVStoreClusterConnector implements ClusterConnector,
        Configurable<MVStoreClusterConnectorConfig> {

    private final MVStoreClusterConnectorConfig configuration =
            new MVStoreClusterConnectorConfig();

    @Getter(lombok.AccessLevel.NONE)
    @Setter(lombok.AccessLevel.NONE)
    private MVStoreCluster cluster;

    @Override
    public synchronized Cluster connect() {
        if (cluster == null) {
            cluster = new MVStoreCluster(configuration);
        }
        return cluster;
    }
}
