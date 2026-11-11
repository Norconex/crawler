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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.MapLoaderLifecycleSupport;
import com.hazelcast.map.MapStore;
import com.norconex.crawler.core.cluster.ClusterException;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * JDBC-based key-value MapStore for Hazelcast OSS. Used in Hazelcast
 * configuration object/files. Both keys and values are strings.
 *
 * Use this store when the persistent representation should remain a
 * raw {@link String}. For typed-in-memory behavior prefer installing a
 * {@link TypedJdbcMapStoreFactory} which will create typed
 * instances that serialize/deserialize to strings for persistence.
 */
@Slf4j
public class StringJdbcMapStore
        implements MapStore<String, String>, MapLoaderLifecycleSupport {

    private static final int TABLE_RECOVERY_MAX_ATTEMPTS = 3;
    private static final Duration TABLE_RECOVERY_RETRY_DELAY =
            Duration.ofMillis(250);

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    private static class Sqls {
        private final String store;
        private final String delete;
        private final String load;
        private final String loadAllKeys;

        Sqls(String tableName, String mergeSql) {
            //--- Store ---
            store = (StringUtils.isNotBlank(mergeSql)
                    ? mergeSql
                    : """
                        MERGE INTO "{tableName}" AS T
                        USING (SELECT ? AS k, ? AS v) AS S
                        ON (T.k = S.k)
                        WHEN MATCHED THEN
                            UPDATE SET T.v = S.v
                        WHEN NOT MATCHED THEN
                            INSERT (k, v)
                            VALUES (S.k, S.v);
                        """)
                            .replace("{tableName}", tableName);

            //--- Delete ---
            delete = "DELETE FROM \"%s\" WHERE k = ?".formatted(tableName);

            //--- Load ---
            load = "SELECT v FROM \"%s\" WHERE k = ?".formatted(tableName);

            //--- Load all keys ---
            loadAllKeys = "SELECT k FROM \"%s\"".formatted(tableName);
        }
    }

    private JdbcClient db;
    private Sqls sqls;
    private String tableName;
    private List<String> tableColumns;

    @Override
    public final void init(
            HazelcastInstance hzInstance,
            Properties storeProps,
            String storeName) {

        db = new JdbcClient(hzInstance, storeProps);
        tableName = storeProps.getProperty(
                JdbcClient.PROP_TABLE_NAME, storeName);
        sqls = new Sqls(tableName, storeProps.getProperty("sql-merge"));
        tableColumns = List.of(
                "k " + storeProps.getProperty(
                        "column-key-type", "VARCHAR(4096)")
                        + " PRIMARY KEY",
                "v " + storeProps.getProperty("column-value-type",
                        "CLOB"));

        LOG.info("Initializing Jdbc map store '{}' with table '{}'.",
                storeName, tableName);
        try {
            db.ensureTableExists(tableName, tableColumns);
        } catch (SQLException e) {
            throw new ClusterException("Can't initialize JDBC map store.", e);
        }
    }

    @Override
    public void destroy() {
        //NOOP No cleanup needed with JDBC store
    }

    @Override
    public void store(@NonNull String key, @NonNull String value) {
        withTableRecovery("store record (key=%s)".formatted(key), () -> {
            try (var conn = db.getConnection();
                    var stmt = conn.prepareStatement(sqls.store)) {
                stmt.setString(1, key);
                stmt.setString(2, value);
                stmt.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void storeAll(Map<String, String> entries) {
        if (entries.isEmpty()) {
            return;
        }
        db.executeInTransaction(conn -> {
            try (var stmt = conn.prepareStatement(sqls.store)) {
                for (var entry : entries.entrySet()) {
                    stmt.setString(1, entry.getKey());
                    stmt.setString(2, entry.getValue());
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
        });
    }

    @Override
    public void delete(String key) {
        withTableRecovery("delete record (key=%s)".formatted(key), () -> {
            try (var conn = db.getConnection();
                    var stmt = conn.prepareStatement(sqls.delete)) {
                stmt.setString(1, key);
                stmt.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void deleteAll(Collection<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        db.executeInTransaction(conn -> {
            try (var stmt = conn.prepareStatement(sqls.delete)) {
                for (var key : keys) {
                    stmt.setString(1, key);
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
        });
    }

    @Override
    public String load(String key) {
        return withTableRecovery("load record (key=%s)".formatted(key), () -> {
            try (var conn = db.getConnection();
                    var stmt = conn.prepareStatement(sqls.load)) {
                stmt.setString(1, key);
                try (var rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString(1);
                    }
                }
            }
            return null;
        });
    }

    @Override
    public Map<String, String> loadAll(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return new HashMap<>();
        }

        return withTableRecovery("load all records", () -> {
            Map<String, String> result = new HashMap<>();
            var sql = "SELECT k, v FROM \"%s\" WHERE k IN (%s)".formatted(
                    tableName,
                    String.join(",", Collections.nCopies(keys.size(), "?")));
            try (var conn = db.getConnection();
                    var stmt = conn.prepareStatement(sql)) {
                var i = 1;
                for (String key : keys) {
                    stmt.setString(i++, key);
                }
                try (var rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString(1), rs.getString(2));
                    }
                }
            }
            return result;
        });
    }

    @Override
    public Iterable<String> loadAllKeys() {
        return withTableRecovery("load all keys", () -> {
            List<String> keys = new ArrayList<>();
            try (var conn = db.getConnection();
                    var stmt = conn.prepareStatement(sqls.loadAllKeys);
                    var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString(1));
                }
            }
            return keys;
        });
    }

    private <T> T withTableRecovery(String action, SqlSupplier<T> op) {
        try {
            return op.get();
        } catch (SQLException firstError) {
            if (!isMissingTableError(firstError)) {
                throw new ClusterException("Failed to %s.".formatted(action),
                        firstError);
            }

            SQLException latestError = firstError;
            for (int attempt = 1; attempt <= TABLE_RECOVERY_MAX_ATTEMPTS;
                    attempt++) {
                LOG.warn("Table '{}' appears missing while trying to {}. "
                        + "Recovery attempt {}/{}.",
                        tableName, action, attempt,
                        TABLE_RECOVERY_MAX_ATTEMPTS);
                try {
                    db.ensureTableExists(tableName, tableColumns);
                } catch (SQLException ensureError) {
                    latestError = ensureError;
                    if (attempt == TABLE_RECOVERY_MAX_ATTEMPTS) {
                        throw new ClusterException(
                                "Failed to initialize missing table '%s' while trying to %s."
                                        .formatted(tableName, action),
                                ensureError);
                    }
                    sleepBeforeRetry();
                    continue;
                }

                try {
                    return op.get();
                } catch (SQLException retryError) {
                    latestError = retryError;
                    if (!isMissingTableError(retryError)
                            || attempt == TABLE_RECOVERY_MAX_ATTEMPTS) {
                        throw new ClusterException(
                                "Failed to %s after table recovery attempts."
                                        .formatted(action),
                                retryError);
                    }
                    sleepBeforeRetry();
                }
            }

            throw new ClusterException(
                    "Failed to %s after table recovery attempts."
                            .formatted(action),
                    latestError);
        }
    }

    private static void sleepBeforeRetry() {
        try {
            Thread.sleep(TABLE_RECOVERY_RETRY_DELAY.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isMissingTableError(SQLException error) {
        SQLException sqlError = error;
        while (sqlError != null) {
            var sqlState = StringUtils.trimToEmpty(sqlError.getSQLState());
            var message = StringUtils
                    .trimToEmpty(sqlError.getMessage())
                    .toLowerCase(Locale.ROOT);
            if ("42P01".equals(sqlState)
                    || (message.contains("relation")
                            && message.contains("does not exist"))
                    || (message.contains("table")
                            && message.contains("not found"))) {
                return true;
            }
            sqlError = sqlError.getNextException();
        }
        return false;
    }
}
