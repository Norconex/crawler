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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.test.TestHazelcastInstanceFactory;

/**
 * Factory helpers for building minimal, isolated Hazelcast instances in
 * component-level tests.  Each instance uses a unique cluster name so it
 * will never join an instance from another test class or test run.
 *
 * <p>Instances are created via a shared {@link TestHazelcastInstanceFactory}
 * which uses an in-memory mock-network layer instead of real TCP sockets.
 * This cuts per-instance startup from ~3–5 s down to ~50 ms.</p>
 */
public final class HazelcastTestSupport {

    static {
        HazelcastBootstrap.configure();
    }

    /**
     * Shared mock-network factory.  All test instances use this factory so
     * they start on the same mock network; unique cluster names (UUIDs)
     * prevent accidental cluster formation across unrelated tests.
     */
    private static final Map<String, TestHazelcastInstanceFactory> FACTORIES =
            new ConcurrentHashMap<>();

    /**
     * Each cluster name gets its own factory, hence its own mock network.
     * A single factory shared by all test classes put every node in the same
     * address space (5701, 5702, ...). With TCP-IP discovery on 127.0.0.1,
     * nodes of unrelated clusters probed and rejected each other, which
     * starved operations and hung the build when classes ran in parallel.
     */
    private static TestHazelcastInstanceFactory factoryFor(Config cfg) {
        return FACTORIES.computeIfAbsent(cfg.getClusterName(),
                k -> new TestHazelcastInstanceFactory());
    }

    /**
     * Starts an instance on the mock network of its cluster name.
     * @param cfg instance configuration
     * @return running instance
     */
    public static HazelcastInstance newInstance(Config cfg) {
        return factoryFor(cfg).newHazelcastInstance(cfg);
    }

    private HazelcastTestSupport() {
    }

    /**
     * Creates a {@link Config} with a random cluster name, no JDBC stores,
     * multicast and auto-detection disabled, and TCP-IP discovery restricted
     * to {@code 127.0.0.1}.  Logging is routed through SLF4J and phone-home
     * is silenced.
     *
     * <p>Callers that need two nodes to form a cluster must use the
     * <em>same</em> cluster-name config — use {@link #buildTestConfig(String)}
     * for that.
     *
     * @return a ready-to-use {@link Config}
     */
    public static Config buildTestConfig() {
        return buildTestConfig("hz-test-" + UUID.randomUUID());
    }

    /**
     * Same as {@link #buildTestConfig()} but with a caller-supplied cluster
     * name.  Use this when two or more instances must discover each other:
     * <pre>
     *   var clusterName = "hz-test-" + UUID.randomUUID();
     *   var node0 = startNode(clusterName);
     *   var node1 = startNode(clusterName);
     * </pre>
     *
     * @param clusterName Hazelcast cluster identifier
     * @return a ready-to-use {@link Config}
     */
    public static Config buildTestConfig(String clusterName) {
        var cfg = new Config();
        cfg.setClusterName(clusterName);
        cfg.setProperty("hazelcast.phone.home.enabled", "false");
        cfg.setProperty("hazelcast.max.join.seconds", "10");

        var join = cfg.getNetworkConfig().getJoin();
        join.getMulticastConfig().setEnabled(false);
        join.getAutoDetectionConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(true).addMember("127.0.0.1");

        return cfg;
    }

    /**
     * Convenience: starts a {@link HazelcastInstance} from a random-name
     * config so the instance is guaranteed to be standalone.
     * Uses the shared {@link TestHazelcastInstanceFactory} mock network.
     *
     * @return running instance
     */
    public static HazelcastInstance startNode() {
        return newInstance(buildTestConfig());
    }

    /**
     * Convenience: starts a {@link HazelcastInstance} using the supplied
     * cluster name.  Multiple instances started with the same name will
     * form an in-process cluster via the mock network.
     * Uses the shared {@link TestHazelcastInstanceFactory} mock network.
     *
     * @param clusterName cluster identifier
     * @return running instance
     */
    public static HazelcastInstance startNode(String clusterName) {
        return newInstance(buildTestConfig(clusterName));
    }

    /**
     * Creates a {@link HazelcastCluster} whose internal
     * {@link HazelcastInstance} is obtained from the shared
     * {@link TestHazelcastInstanceFactory} mock network instead of real TCP.
     * Drop-in replacement for {@code new HazelcastCluster(config)} in tests.
     *
     * @param config cluster connector configuration
     * @return test-friendly HazelcastCluster
     */
    public static HazelcastCluster newCluster(
            HazelcastClusterConnectorConfig config) {
        return new HazelcastCluster(config) {
            @Override
            protected HazelcastInstance createHazelcastInstance(
                    Config hzConfig) {
                return newInstance(hzConfig);
            }
        };
    }

    /**
     * Shuts down all {@link HazelcastInstance}s created by the shared
     * test factory.  Call from {@code @AfterEach} or {@code @AfterAll}.
     */
    public static void shutdownAll() {
        FACTORIES.values().forEach(TestHazelcastInstanceFactory::shutdownAll);
        FACTORIES.clear();
    }
}
