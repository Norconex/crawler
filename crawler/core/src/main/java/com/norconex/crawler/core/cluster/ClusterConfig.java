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

import com.norconex.crawler.core.cluster.impl.mvstore.MVStoreClusterConnector;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ClusterConfig {
    /**
     * Default port used to communicate via a node for some administrative
     * crawler cluster tasks.
     */
    public static final int DEFAULT_ADMIN_PORT = 27295;

    /**
     * {@link #setAdminBindAddress(String)} value binding the administrative
     * server to the loopback interface only, reachable from the host running
     * the crawler and nowhere else.
     */
    public static final String ADMIN_BIND_LOOPBACK = "loopback";

    /**
     * {@link #setAdminBindAddress(String)} value binding the administrative
     * server to every network interface, so other hosts can reach it.
     */
    public static final String ADMIN_BIND_ANY = "any";

    /**
     * Default administrative server bind address: loopback only.
     */
    public static final String DEFAULT_ADMIN_BIND_ADDRESS =
            ADMIN_BIND_LOOPBACK;

    /**
     * The connector to the cluster implementation used to run the crawler.
     * Default is an MVStore file-backed connector with no external
     * infrastructure. For clustered mode, set this to a
     * {@link com.norconex.crawler.core.cluster.impl.hazelcast.HazelcastClusterConnector}.
     * For a pure in-memory non-persistent alternative, use
     * {@link com.norconex.crawler.core.cluster.impl.memory.MemoryClusterConnector}.
     */
    private ClusterConnector connector =
            new MVStoreClusterConnector();
    /**
     * Disable launching the crawler administrative server endpoints.
     */
    private boolean adminDisabled;
    /**
     * Port the crawler cluster listens to for administrative commands,
     * on each nodes. Incremented
     * to the next available port in case of conflicts.
     * Default is 27295 (mnemonic: ‘CRAWL’ on a phone keypad).
     */
    private int adminPort = DEFAULT_ADMIN_PORT;

    /**
     * <p>
     * Network interface the administrative server binds to. Accepted values:
     * </p>
     * <ul>
     *   <li>
     *     {@value #ADMIN_BIND_LOOPBACK} (default) &mdash; the loopback
     *     interface only. The administrative endpoints are then reachable
     *     from the host running the crawler and from nowhere else.
     *   </li>
     *   <li>
     *     {@value #ADMIN_BIND_ANY} &mdash; every interface. Required for
     *     clustered mode, where nodes and the <code>stop</code> command may
     *     run on other hosts.
     *   </li>
     *   <li>
     *     any host name or IP address &mdash; that interface only.
     *   </li>
     * </ul>
     * <p>
     * The default is deliberately the restrictive one. The administrative
     * endpoints include stopping the crawl, and they are guarded only by a
     * <code>crawler-id</code> request header, which is an identifier taken
     * from the configuration rather than a secret. Exposing them beyond the
     * host means anyone who can reach the port and name the crawler can halt
     * a crawl, so widening this is an explicit decision.
     * </p>
     */
    private String adminBindAddress = DEFAULT_ADMIN_BIND_ADDRESS;

    /**
     * Whether the crawler should run in stand-alone or cluster mode.
     * Default is standalone, optimized for performance, by eliminating or
     * minimizing inter-node communication.
     */
    private boolean clustered;
}
