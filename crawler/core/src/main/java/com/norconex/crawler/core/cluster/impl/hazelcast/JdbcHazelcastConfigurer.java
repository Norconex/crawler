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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;

import com.hazelcast.config.Config;
import com.hazelcast.config.DataConnectionConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MapStoreConfig;
import com.hazelcast.config.MapStoreConfig.InitialLoadMode;
import com.hazelcast.config.QueueConfig;
import com.hazelcast.config.QueueStoreConfig;
import com.norconex.crawler.core.cluster.impl.hazelcast.jdbc.TypedJdbcMapStoreFactory;
import com.norconex.crawler.core.cluster.impl.hazelcast.jdbc.TypedJdbcQueueStoreFactory;
import com.norconex.crawler.core.cluster.pipeline.StepRecord;
import com.norconex.crawler.core.ledger.CrawlerEntry;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Default {@link HazelcastConfigurer} implementation that builds the Hazelcast
 * configuration programmatically using a JDBC data source for persistence.
 *
 * <p>
 * <strong>Standalone mode</strong> (default, {@code clustered = false}):
 * An embedded H2 file-based database stored in the crawler's work directory is
 * used automatically — no external configuration is required.
 * </p>
 * <p>
 * <strong>Clustered mode</strong> ({@code clustered = true}):
 * An external database must be supplied via {@link #setJdbcUrl(String)} (plus
 * optionally {@link #setJdbcUsername(String)}, {@link #setJdbcPassword(String)}
 * , {@link #setJdbcDriver(String)}). The default SQL merge statement uses
 * PostgreSQL {@code INSERT ... ON CONFLICT} syntax; set
 * {@link #setSqlMerge(String)} to target a different database dialect.
 * </p>
 *
 * <table border="1">
 *   <caption>Effective defaults by mode</caption>
 *   <tr><th>Setting</th><th>Standalone</th><th>Clustered</th></tr>
 *   <tr><td>backupCount</td><td>0</td><td>1</td></tr>
 *   <tr><td>maxPoolSize</td><td>10</td><td>20</td></tr>
 *   <tr><td>jdbcUrl</td><td>H2 file in workDir</td><td>must be set</td></tr>
 *   <tr><td>sqlMerge</td><td>H2 MERGE syntax</td>
 *       <td>PostgreSQL INSERT … ON CONFLICT</td></tr>
 * </table>
 */
@Data
@Accessors(chain = true)
public class JdbcHazelcastConfigurer implements HazelcastConfigurer {

    private static final Map<String, String> STANDALONE_HAZELCAST_DEFAULTS =
            Map.ofEntries(
                    Map.entry("hazelcast.operation.thread.count", "2"),
                    Map.entry(
                            "hazelcast.operation.generic.thread.count",
                            "2"),
                    Map.entry(
                            "hazelcast.operation.priority.generic.thread.count",
                            "1"),
                    Map.entry(
                            "hazelcast.operation.response.thread.count",
                            "1"),
                    Map.entry("hazelcast.event.thread.count", "2"),
                    Map.entry("hazelcast.io.input.thread.count", "1"),
                    Map.entry("hazelcast.io.output.thread.count", "1"),
                    Map.entry("hazelcast.metrics.enabled", "false"),
                    Map.entry(
                            "hazelcast.slow.operation.detector.enabled",
                            "false"));

    /** Factory class for JDBC-backed map stores. */
    public static final String MAP_STORE_FACTORY_CLASS =
            TypedJdbcMapStoreFactory.class.getName();

    /** Factory class for JDBC-backed queue stores. */
    public static final String QUEUE_STORE_FACTORY_CLASS =
            TypedJdbcQueueStoreFactory.class.getName();

    /** Name of the shared JDBC data-connection referenced by store configs. */
    public static final String DATA_CONNECTION_REF = "jdbc-datasource";

    // H2-compatible MERGE (standalone default)
    private static final String H2_SQL_MERGE =
            "MERGE INTO \"{tableName}\" (k, v)\nKEY(k)\nVALUES (?, ?)";

    // PostgreSQL-compatible UPSERT (clustered default)
    private static final String PG_SQL_MERGE =
            "INSERT INTO \"{tableName}\" (k, v)\n"
                    + "VALUES (?, ?)\n"
                    + "ON CONFLICT (k) DO UPDATE SET v = EXCLUDED.v";

    // -----------------------------------------------------------------
    // JDBC settings
    // -----------------------------------------------------------------

    /**
     * JDBC URL for the data source.
     * In standalone mode, defaults to an H2 file database stored inside the
     * crawler work directory. In clustered mode this must be set explicitly.
     */
    private String jdbcUrl;

    /** JDBC username. Defaults to {@code "sa"} (H2 default). */
    private String jdbcUsername = "sa";

    /** JDBC password. Defaults to empty string. */
    private String jdbcPassword = "";

    /**
     * JDBC driver class name. Optional; HikariCP auto-detects the driver
     * from the URL when this is not set.
     */
    private String jdbcDriver;

    /**
     * Maximum JDBC connection pool size.
     * {@code 0} (default) means automatic: 10 for standalone, 20 for
     * clustered.
     */
    private int maxPoolSize;

    // -----------------------------------------------------------------
    // Map-store settings
    // -----------------------------------------------------------------

    /**
     * Write-behind delay in seconds for data maps (ledger, session, etc.).
     * {@code 0} makes writes synchronous (write-through).
     * Default is {@code 5}, which batches writes for significantly better
     * throughput. Pipeline coordination maps always use write-through
     * regardless of this setting.
     */
    private int writeDelaySeconds = 5;

    /**
     * Maximum number of entries per write-behind batch.
     * Only effective when {@link #writeDelaySeconds} &gt; 0.
     * Default is {@code 100}.
     */
    private int writeBatchSize = 100;

    /**
     * Whether to coalesce write-behind updates so that only the latest
     * value for each key is persisted per batch.
     * Only effective when {@link #writeDelaySeconds} &gt; 0.
     * Default is {@code true}.
     */
    private boolean writeCoalescing = true;

    /**
     * SQL column type for map/queue keys. Defaults to
     * {@code "VARCHAR(4096)"}.
     */
    private String columnKeyType = "VARCHAR(4096)";

    /**
     * SQL column type for map values. Defaults to {@code "TEXT"}.
     */
    private String columnValueType = "TEXT";

    /**
     * SQL merge/upsert template used for map stores. The token
     * {@code {tableName}} is replaced at runtime with the actual table name.
     * <p>
     * Defaults to H2 {@code MERGE … KEY …} syntax for standalone and
     * PostgreSQL {@code INSERT … ON CONFLICT …} syntax for clustered.
     * Override this when targeting a different database dialect.
     * </p>
     */
    private String sqlMerge;

    /**
     * Hazelcast map-store initial load mode.
     * {@link InitialLoadMode#LAZY} (default) defers loading until an entry
     * is accessed, which speeds up startup. Critical maps such as the crawl
     * ledger call {@code loadAll()} explicitly when they are first used so
     * that operations like {@code size()} and iteration return correct
     * results. {@link InitialLoadMode#EAGER} loads all entries as soon as
     * the cluster member starts.
     */
    private InitialLoadMode initialLoadMode = InitialLoadMode.LAZY;

    // -----------------------------------------------------------------
    // Cluster settings
    // -----------------------------------------------------------------

    /**
     * Hazelcast backup count.
     * {@code -1} (default) means automatic: 0 for standalone, 1 for
     * clustered.
     */
    private int backupCount = -1;

    /**
     * TCP/IP member list for clustered discovery, as a comma-separated list of
     * {@code host:port} addresses (e.g.,
     * {@code "192.168.1.10:5701,192.168.1.11:5701"}).
     * Defaults to {@code "127.0.0.1:5701,127.0.0.1:5702,127.0.0.1:5703"}
     * when in clustered mode and not otherwise specified.
     */
    private String tcpMembers;

    /**
     * Whether to use Hazelcast auto-discovery in clustered mode.
     * <p>
     * Default is {@code false} to keep deterministic TCP member discovery.
     * When set to {@code true}, auto-detection is enabled and explicit
     * TCP-member discovery is disabled.
     * </p>
     */
    private boolean autoDiscoveryEnabled;

    /**
     * Whether Hazelcast Jet engine should be enabled.
     * <p>
     * {@code null} (default) means enabled for backward compatibility.
     * Set to {@code false} when Jet is not used and a leaner cluster runtime
     * is preferred (e.g., deterministic integration tests).
     * </p>
     */
    private Boolean jetEnabled;

    /**
     * Whether queue-store persistence should be enabled for Hazelcast queues.
     * <p>
     * Defaults to {@code true}. Set to {@code false} for tests that do not
     * require persistent queue recovery and prioritize startup/runtime speed.
     * </p>
     */
    private boolean queueStoreEnabled = true;

    // -----------------------------------------------------------------
    // Advanced / escape-hatch
    // -----------------------------------------------------------------

    /**
     * Additional Hazelcast system properties set on the config (e.g.
     * {@code "hazelcast.partition.count"} → {@code "17"}).
     * Applied after all other settings are built.
     */
    private Map<String, String> hazelcastProperties = new LinkedHashMap<>();

    // -----------------------------------------------------------------
    // HazelcastConfigurer implementation
    // -----------------------------------------------------------------

    @Override
    public Config buildConfig(HazelcastConfigurerContext ctx) {
        HazelcastBootstrap.configure();

        var config = new Config();
        config.setClusterName(ctx.clusterName());

        var effectiveBackupCount = resolveBackupCount(ctx.clustered());
        var effectiveSqlMerge = resolveSqlMerge(ctx.clustered());
        var effectiveJdbcUrl = resolveJdbcUrl(ctx);
        var effectivePoolSize = resolvePoolSize(ctx.clustered());

        addDataConnection(config, effectiveJdbcUrl, effectivePoolSize);
        configureJet(config, ctx.clustered(), effectiveBackupCount);
        configureNetwork(config, ctx.clustered());
        addMapConfigs(config, effectiveBackupCount, effectiveSqlMerge);
        addQueueConfigs(config, effectiveBackupCount);
        applyStandaloneDefaults(config, ctx.clustered());
        applyHazelcastProperties(config);

        return config;
    }

    // -----------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------

    private int resolveBackupCount(boolean clustered) {
        return backupCount >= 0 ? backupCount : (clustered ? 1 : 0);
    }

    private String resolveSqlMerge(boolean clustered) {
        return sqlMerge != null ? sqlMerge
                : (clustered ? PG_SQL_MERGE : H2_SQL_MERGE);
    }

    private String resolveJdbcUrl(HazelcastConfigurerContext ctx) {
        if (jdbcUrl != null) {
            return jdbcUrl;
        }
        // Default: embedded H2 file database in the crawler work directory.
        return "jdbc:h2:file:"
                + ctx.workDir().toAbsolutePath().toString().replace('\\', '/')
                + "/db;DB_CLOSE_DELAY=-1";
    }

    private int resolvePoolSize(boolean clustered) {
        return maxPoolSize > 0 ? maxPoolSize : (clustered ? 20 : 10);
    }

    private void addDataConnection(Config cfg, String url, int poolSize) {
        var props = new Properties();
        props.setProperty("jdbcUrl", url);
        props.setProperty("username",
                jdbcUsername != null ? jdbcUsername : "sa");
        props.setProperty("password",
                jdbcPassword != null ? jdbcPassword : "");
        props.setProperty("maximumPoolSize", String.valueOf(poolSize));
        if (StringUtils.isNotBlank(jdbcDriver)) {
            props.setProperty("driverClassName", jdbcDriver);
        }

        var dc = new DataConnectionConfig();
        dc.setName(DATA_CONNECTION_REF);
        dc.setType("JDBC");
        dc.setShared(true);
        dc.setProperties(props);
        cfg.addDataConnectionConfig(dc);
    }

    private void configureJet(Config cfg, boolean clustered, int backups) {
        var effectiveJetEnabled = jetEnabled == null
                || jetEnabled.booleanValue();
        var jet = cfg.getJetConfig();
        jet.setEnabled(effectiveJetEnabled);
        if (!effectiveJetEnabled) {
            return;
        }
        if (clustered && backups > 0) {
            jet.setBackupCount(backups);
        }
    }

    private void configureNetwork(Config cfg, boolean clustered) {
        var net = cfg.getNetworkConfig();
        var join = net.getJoin();

        // Keep multicast disabled by default. Discovery is either explicit TCP
        // members or Hazelcast auto-detection when enabled.
        join.getMulticastConfig().setEnabled(false);
        if (join.getAutoDetectionConfig() != null) {
            join.getAutoDetectionConfig().setEnabled(false);
        }
        join.getTcpIpConfig().setEnabled(false);

        if (!clustered) {
            net.setPortAutoIncrement(true);
            return;
        }

        if (autoDiscoveryEnabled) {
            if (join.getAutoDetectionConfig() != null) {
                join.getAutoDetectionConfig().setEnabled(true);
            }
            net.setPortAutoIncrement(true);
            return;
        }

        // Clustered: configure TCP/IP discovery.
        var members = parseTcpMembers();
        var tcp = join.getTcpIpConfig();
        tcp.setEnabled(true);
        tcp.setMembers(members);

        // Auto-compute port range from the member list to ensure all
        // members can bind within the advertised range.
        int minPort = 5701;
        int maxPort = 5703;
        boolean sawPort = false;
        for (var m : members) {
            var idx = m.lastIndexOf(':');
            if (idx > 0 && idx < m.length() - 1) {
                try {
                    var p = Integer.parseInt(m.substring(idx + 1));
                    if (!sawPort) {
                        minPort = maxPort = p;
                        sawPort = true;
                    } else {
                        minPort = Math.min(minPort, p);
                        maxPort = Math.max(maxPort, p);
                    }
                } catch (NumberFormatException e) {
                    // ignore non-numeric port
                }
            }
        }
        net.setPort(minPort);
        net.setPortAutoIncrement(true);
        net.setPortCount(Math.max(1, (maxPort - minPort) + 1));
    }

    private List<String> parseTcpMembers() {
        var raw = tcpMembers != null
                ? tcpMembers
                : "127.0.0.1:5701,127.0.0.1:5702,127.0.0.1:5703";
        var list = new ArrayList<String>();
        for (var part : StringUtils.split(raw, ',')) {
            var m = StringUtils.trimToNull(part);
            if (m != null) {
                list.add(m);
            }
        }
        return list;
    }

    private void addMapConfigs(Config cfg, int backups,
            String effectiveSqlMerge) {
        // Default / catch-all map config (write-behind).
        cfg.addMapConfig(buildMapConfig("default", backups, null,
                effectiveSqlMerge, false));

        // Pipe maps: write-through (synchronous) because they coordinate
        // pipeline steps across nodes and must never be stale.
        var stepClass = StepRecord.class.getName();
        cfg.addMapConfig(
                buildMapConfig("pipeCurrentStep", backups, stepClass,
                        effectiveSqlMerge, true));
        cfg.addMapConfig(
                buildMapConfig("pipeWorkerStatuses", backups, stepClass,
                        effectiveSqlMerge, true));

        // Ledger wildcard: value-class-name is the base type here, but it is
        // overridden at startup by HazelcastCluster.applyCacheTypes() with the
        // concrete CrawlerEntry subclass registered by the driver.
        cfg.addMapConfig(buildMapConfig("ledger_*", backups,
                CrawlerEntry.class.getName(), effectiveSqlMerge, false));

        // Ephemeral maps — in-memory only, no persistence.
        var ephStore = new MapStoreConfig();
        ephStore.setEnabled(false);
        var ephConfig = new MapConfig("eph-*");
        ephConfig.setBackupCount(backups);
        ephConfig.setMapStoreConfig(ephStore);
        cfg.addMapConfig(ephConfig);
    }

    private MapConfig buildMapConfig(
            String name, int backups,
            String valueClassName, String effectiveSqlMerge,
            boolean writeThrough) {
        var props = new Properties();
        if (StringUtils.isNotBlank(valueClassName)) {
            props.setProperty("value-class-name", valueClassName);
        }
        props.setProperty("data-connection-ref", DATA_CONNECTION_REF);
        props.setProperty("column-key-type", columnKeyType);
        props.setProperty("column-value-type", columnValueType);
        props.setProperty("sql-merge", effectiveSqlMerge);

        var storeConfig = new MapStoreConfig();
        storeConfig.setEnabled(true);
        storeConfig.setOffload(true);
        storeConfig.setInitialLoadMode(initialLoadMode);
        storeConfig.setFactoryClassName(MAP_STORE_FACTORY_CLASS);
        storeConfig.setProperties(props);

        if (writeThrough) {
            storeConfig.setWriteDelaySeconds(0);
        } else {
            storeConfig.setWriteDelaySeconds(writeDelaySeconds);
            if (writeDelaySeconds > 0) {
                storeConfig.setWriteBatchSize(writeBatchSize);
                storeConfig.setWriteCoalescing(writeCoalescing);
            }
        }

        var mapConfig = new MapConfig(name);
        mapConfig.setBackupCount(backups);
        mapConfig.setMapStoreConfig(storeConfig);
        return mapConfig;
    }

    private void addQueueConfigs(Config cfg, int backups) {
        var storeProps = new Properties();
        storeProps.setProperty("data-connection-ref", DATA_CONNECTION_REF);
        storeProps.setProperty("column-value-type", "VARCHAR(4096)");
        storeProps.setProperty("value-class-name", String.class.getName());

        var storeConfig = new QueueStoreConfig();
        storeConfig.setEnabled(queueStoreEnabled);
        storeConfig.setFactoryClassName(QUEUE_STORE_FACTORY_CLASS);
        storeConfig.setProperties(storeProps);

        var queueConfig = new QueueConfig("queue-*");
        queueConfig.setBackupCount(backups);
        queueConfig.setQueueStoreConfig(storeConfig);
        cfg.addQueueConfig(queueConfig);
    }

    private void applyStandaloneDefaults(Config cfg, boolean clustered) {
        if (clustered) {
            return;
        }
        for (var entry : STANDALONE_HAZELCAST_DEFAULTS.entrySet()) {
            if (cfg.getProperty(entry.getKey()) == null) {
                cfg.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    private void applyHazelcastProperties(Config cfg) {
        if (hazelcastProperties == null) {
            return;
        }
        for (var entry : hazelcastProperties.entrySet()) {
            cfg.setProperty(entry.getKey(), entry.getValue());
        }
    }
}
