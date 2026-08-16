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
package com.norconex.crawler.core.cluster.impl.hazelcast;

import java.time.Duration;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class HazelcastClusterConnectorConfig {

    /**
     * The configurer responsible for building the Hazelcast
     * {@link com.hazelcast.config.Config}.
     * Defaults to {@link JdbcHazelcastConfigurer}, which uses JDBC persistence
     * with an embedded H2 database in standalone mode and requires an external
     * JDBC URL in clustered mode.
     * <p>
     * Advanced users can supply a custom {@link HazelcastConfigurer}
     * implementation, or use
     * {@link com.norconex.crawler.core.cluster.impl.hazelcast.HazelcastConfigLoader}
     * inside one to retain YAML/XML file-based configuration.
     * </p>
     */
    private HazelcastConfigurer configurer = new JdbcHazelcastConfigurer();

    /**
     * Cluster name for Hazelcast instance. All nodes with the same cluster
     * name will form a cluster. Defaults to {@code "crawler-cluster"}.
     */
    private String clusterName = "crawler-cluster";

    /**
     * Unique name for the Hazelcast instance. Useful when running multiple
     * nodes in the same JVM (e.g., during tests). If not set, a random
     * UUID will be generated.
     * @since 4.0.0
     */
    private String instanceName;

    /**
     * Maximum amount of time to wait before declaring a node as
     * "expired" when running a crawler task across multiple nodes.
     * <p>
     * The absolute minimum value is 5 seconds; any configured value
     * below this threshold will be clamped to 5 seconds at runtime.
     * Defaults to 30 seconds. Not applicable when running in
     * standalone mode.
     * </p>
     */
    private Duration nodeExpiryTimeout = Duration.ofSeconds(30);

    /**
     * Interval at which worker nodes send heartbeat updates for their
     * current pipeline status to the coordinator.
     * <p>
     * The default is 1 second. At runtime, the effective heartbeat
     * interval is validated against the configured node expiry
     * timeout to ensure it does not exceed one third of the expiry
     * interval. If it does, it will be reduced to that maximum
     * allowed value. The absolute minimum heartbeat interval is
     * 500 milliseconds.
     * </p>
     */
    private Duration workerHeartbeatInterval = Duration.ofSeconds(1);
}
