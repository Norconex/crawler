/* Copyright 2026 Norconex Inc.
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

package com.norconex.crawler.core.cluster.impl.hazelcast.jdbc;

import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import com.hazelcast.collection.QueueStore;
import com.hazelcast.core.HazelcastInstance;
import com.norconex.crawler.core.cluster.ClusterException;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * JDBC-backed Hazelcast QueueStore implementation that persists
 * queue items in a text column in a table with an order column.
 * The store always uses the DataSource obtained from the provided
 * HazelcastInstance (via JdbcClient) and does not fall back to an
 * embedded H2 datasource.
 */
@Slf4j
public class StringJdbcQueueStore implements QueueStore<String> {

    private static class Sqls {
        private final String store;
        private final String delete;
        private final String load;
        private final String loadAllKeys;

        Sqls(String tableName) {
            //--- Store ---
            store = "INSERT INTO \"%s\" (k, v) VALUES (?, ?)"
                    .formatted(tableName);

            //--- Delete ---
            delete = "DELETE FROM \"%s\" WHERE k = ?".formatted(tableName);

            //--- Load ---
            load = "SELECT v FROM \"%s\" WHERE k = ?".formatted(tableName);

            //--- Load all keys ---
            loadAllKeys =
                    "SELECT k FROM \"%s\" ORDER BY k".formatted(tableName);
        }
    }

    private final JdbcClient db;
    private final Sqls sqls;
    private final String tableName;

    /**
     * Construct the queue store using the DataSource attached to the
     * provided HazelcastInstance. This constructor is mandatory: the
     * store will not attempt to create an embedded DataSource.
     * @param hzInstance Hazelcast instance
     * @param storeName store name
     * @param storeProps store properties
     */
    public StringJdbcQueueStore(
            @NonNull HazelcastInstance hzInstance,
            @NonNull String storeName,
            @NonNull Properties storeProps) {

        db = new JdbcClient(hzInstance, storeProps);
        tableName = storeProps.getProperty(
                JdbcClient.PROP_TABLE_NAME, storeName);
        sqls = new Sqls(tableName);

        LOG.info("Initializing Jdbc queue store '{}' with table '{}'.",
                storeName, tableName);

        // Ensure table exists using the cross-database helper.
        try {
            db.ensureTableExists(tableName, java.util.List.of(
                    "k BIGINT PRIMARY KEY",
                    "v " + storeProps.getProperty("column-value-type",
                            "VARCHAR(4096)")));
        } catch (SQLException e) {
            throw new ClusterException("Can't initialize JDBC queue store.", e);
        }
    }

    @Override
    public void store(Long key, String value) {
        try (var conn = db.getConnection();
                var stmt = conn.prepareStatement(sqls.store)) {
            stmt.setLong(1, key);
            stmt.setString(2, value);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new ClusterException("Could not store queue entry", e);
        }
    }

    @Override
    public void storeAll(Map<Long, String> entries) {
        if (entries.isEmpty()) {
            return;
        }
        db.executeInTransaction(conn -> {
            try (var stmt = conn.prepareStatement(sqls.store)) {
                for (var entry : entries.entrySet()) {
                    stmt.setLong(1, entry.getKey());
                    stmt.setString(2, entry.getValue());
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
        });
    }

    @Override
    public void delete(Long key) {
        try (var conn = db.getConnection();
                var ps = conn.prepareStatement(sqls.delete)) {
            ps.setLong(1, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ClusterException("Could not delete queue entry", e);
        }
    }

    @Override
    public void deleteAll(Collection<Long> keys) {
        db.executeInTransaction(conn -> {
            try (var stmt = conn.prepareStatement(sqls.delete)) {
                for (var key : keys) {
                    stmt.setLong(1, key);
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
        });
    }

    @Override
    public String load(Long key) {
        try (var conn = db.getConnection();
                var stmt = conn.prepareStatement(sqls.load)) {
            stmt.setLong(1, key);
            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (SQLException e) {
            throw new ClusterException("Could not load queue entry", e);
        }
        return null;
    }

    @Override
    public Map<Long, String> loadAll(Collection<Long> keys) {
        Map<Long, String> map = new LinkedHashMap<>();
        if (keys == null || keys.isEmpty()) {
            return map;
        }

        var sql = ("SELECT k, v FROM \"%s\" WHERE k IN ("
                + String.join(",",
                        keys.stream().map(k -> "?").toArray(String[]::new))
                + ") ORDER BY k").formatted(tableName);
        try (var conn = db.getConnection();
                var ps = conn.prepareStatement(sql)) {
            var i = 1;
            for (Long k : keys) {
                ps.setLong(i++, k);
            }
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getLong(1), rs.getString(2));
                }
            }
        } catch (SQLException e) {
            throw new ClusterException("Could not loadAll queue entries", e);
        }
        return map;
    }

    @Override
    public Set<Long> loadAllKeys() {
        Set<Long> keys = new TreeSet<>();
        try (var conn = db.getConnection();
                var ps = conn.prepareStatement(sqls.loadAllKeys);
                var rs = ps.executeQuery()) {
            while (rs.next()) {
                keys.add(rs.getLong(1));
            }
        } catch (SQLException e) {
            throw new ClusterException("Could not loadAllKeys for queue", e);
        }
        return keys;
    }
}
