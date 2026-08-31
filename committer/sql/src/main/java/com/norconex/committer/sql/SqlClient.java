/* Copyright 2023-2024 Norconex Inc.
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
package com.norconex.committer.sql;

import static org.apache.commons.lang3.StringUtils.join;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.commons.dbutils.QueryRunner;
import org.apache.commons.dbutils.ResultSetHandler;
import org.apache.commons.dbutils.handlers.ScalarHandler;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringSubstitutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.CommitterRequest;
import com.norconex.committer.core.CommitterUtil;
import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.commons.lang.encrypt.EncryptionUtil;

/**
 * <p>
 * Simple SQL client.
 * </p>
 */
class SqlClient {

    private static final Logger LOG = LoggerFactory.getLogger(SqlClient.class);

    private final SqlCommitterConfig cfg;

    // When we create missing ones... so we do not check if exists each time.
    // key = field name; value = field size
    private final Map<String, Integer> existingFields = new HashMap<>();
    private BasicDataSource datasource;
    private QueryRunner queryRunner; // thread-safe

    //--- INIT -----------------------------------------------------------------

    public SqlClient(SqlCommitterConfig config) throws CommitterException {
        cfg = config;
        if (StringUtils.isBlank(cfg.getDriverClass())) {
            throw new CommitterException("No driver class specified.");
        }
        if (StringUtils.isBlank(cfg.getConnectionUrl())) {
            throw new CommitterException("No connection URL specified.");
        }
        if (StringUtils.isBlank(cfg.getTableName())) {
            throw new CommitterException("No table name specified.");
        }
        if (StringUtils.isBlank(cfg.getPrimaryKey())) {
            throw new CommitterException("No primary key specified.");
        }
        datasource = createDataSource();
        queryRunner = new QueryRunner(datasource);
        ensureTable();
    }

    private BasicDataSource createDataSource() throws CommitterException {
        var ds = new BasicDataSource(); //NOSONAR. closed in close()
        // if path is blank, we assume it is already in classpath
        if (StringUtils.isNotBlank(cfg.getDriverPath())) {
            try {
                var file = new File(cfg.getDriverPath());
                if (!file.exists()) {
                    throw new FileNotFoundException(
                            "Driver file not found: " + file);
                }
                ds.setDriverClassLoader(new URLClassLoader(
                        new URL[] { file.toURI().toURL() },
                        getClass().getClassLoader()));
            } catch (MalformedURLException | FileNotFoundException e) {
                throw new CommitterException(
                        "Invalid driver path: " + cfg.getDriverPath(), e);
            }
        }
        ds.setDriverClassName(cfg.getDriverClass());
        ds.setUrl(cfg.getConnectionUrl());
        ds.setDefaultAutoCommit(true);
        if (cfg.getCredentials().isSet()) {
            ds.setUsername(cfg.getCredentials().getUsername());
            ds.setPassword(
                    EncryptionUtil.decrypt(
                            cfg.getCredentials().getPassword(),
                            cfg.getCredentials().getPasswordKey()));
        }
        for (Entry<String, List<String>> en : cfg.getProperties().entrySet()) {
            en.getValue().forEach(
                    v -> ds.addConnectionProperty(en.getKey(), v));
        }
        return ds;
    }

    private void ensureTable() throws CommitterException {
        // if table was verified or no CREATE statement specified,
        // return right away.
        if (StringUtils.isBlank(cfg.getCreateTableSQL())) {
            return;
        }
        try {
            LOG.info("Checking if table \"{}\" exists...", cfg.getTableName());
            if (!tableExists()) {
                LOG.info(
                        "Table \"{}\" does not exist. "
                                + "Attempting to create it...",
                        cfg.getTableName());
                var sql = interpolate(cfg.getCreateTableSQL(), null);
                LOG.debug(sql);
                queryRunner.update(sql);
                LOG.info("Table \"{}\" created.", cfg.getTableName());
            } else {
                LOG.info("Table \"{}\" exists.", cfg.getTableName());
            }
            loadFieldsMetadata();
        } catch (SQLException e) {
            throw new CommitterException(
                    "Could not create table \"" + cfg.getTableName() + "\".");
        }
    }

    private boolean tableExists() {
        try {
            // for table existence, we cannot rely enough on return value
            // so we rely on exception.
            runExists(null);
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private void loadFieldsMetadata() throws SQLException {
        // Add existing field info
        queryRunner.query(
                "SELECT * FROM " + cfg.getTableName(),
                (ResultSetHandler<Void>) rs -> {
                    var metadata = rs.getMetaData();
                    for (var i = 1; i <= metadata.getColumnCount(); i++) {
                        existingFields.put(
                                StringUtils.lowerCase(
                                        metadata.getColumnLabel(i),
                                        Locale.ENGLISH),
                                metadata.getColumnDisplaySize(i));
                    }
                    return null;
                });
    }

    //--- POST -----------------------------------------------------------------

    public void post(Iterator<CommitterRequest> it) throws CommitterException {

        var upsertCount = 0;
        var deleteCount = 0;
        try {
            while (it.hasNext()) {
                var req = it.next();
                if (req instanceof UpsertRequest upsert) {
                    dbUpsert(upsert);
                    upsertCount++;
                } else if (req instanceof DeleteRequest delete) {
                    dbDelete(delete);
                    deleteCount++;
                } else {
                    throw new CommitterException("Unsupported request: " + req);
                }
            }
            LOG.info(
                    "Sent {} upserts and {} deletes to database.",
                    upsertCount, deleteCount);
        } catch (CommitterException e) {
            throw e;
        } catch (Exception e) {
            throw new CommitterException(
                    "Could not commit batch to database.", e);
        }
    }

    private void dbUpsert(UpsertRequest req)
            throws SQLException, CommitterException {

        var meta = req.getMetadata();

        // For doc content stream, if target field is already set,
        // the doc content is ignored.
        if (StringUtils.isNotBlank(cfg.getTargetContentField())
                && !isTargetFieldAlreadySet(
                        req, "content", cfg.getTargetContentField())) {
            meta.set(
                    cfg.getTargetContentField(),
                    CommitterUtil.getContentAsString(req));
        }

        // resolved must be called before creating SQL query fields/values
        var pkValue = resolvePkValue(req);

        List<String> fields = new ArrayList<>();
        List<String> values = new ArrayList<>();

        for (Entry<String, List<String>> entry : meta.entrySet()) {
            var field = entry.getKey();
            var value = join(entry.getValue(), cfg.getMultiValuesJoiner());
            fields.add(fixFieldName(field));
            values.add(value);
        }

        var sql = "INSERT INTO " + cfg.getTableName() + "("
                + StringUtils.join(fields, ",")
                + ") VALUES (" + StringUtils.repeat("?", ", ", values.size())
                + ")";
        if (LOG.isTraceEnabled()) {
            LOG.trace("SQL: {}", sql);
        }
        sqlInsertDoc(sql, pkValue, fields, values);
    }

    private void dbDelete(DeleteRequest req) throws SQLException {
        runDelete(resolvePkValue(req));
    }

    private String resolvePkValue(CommitterRequest req) {
        var meta = req.getMetadata();

        // For doc reference, if primary key field is already set,
        // the doc reference is ignored.
        if (!isTargetFieldAlreadySet(req, "reference", cfg.getPrimaryKey())) {
            meta.set(cfg.getPrimaryKey(), req.getReference());
        }
        return meta.getString(cfg.getPrimaryKey());
    }

    //--- CLOSE ----------------------------------------------------------------

    public void close() throws CommitterException {
        if (datasource != null) {
            try {
                datasource.close();
            } catch (SQLException e) {
                throw new CommitterException("Could not close datasource.", e);
            }
            datasource = null;
            queryRunner = null;
            existingFields.clear();
        }
    }

    //--- MISC -----------------------------------------------------------------

    private boolean recordExists(String id) throws SQLException {
        return runExists(cfg.getPrimaryKey() + " = ?", id);
    }

    private boolean runExists(String where, Object... values)
            throws SQLException {
        var sql = "SELECT 1 FROM " + cfg.getTableName();
        if (StringUtils.isNotBlank(where)) {
            sql += " WHERE " + where;
        }
        LOG.debug(sql);
        var val = (Number) queryRunner.query(
                sql, new ScalarHandler<>(), values);
        return val != null && val.longValue() == 1;
    }

    private void runDelete(String docId) throws SQLException {
        var deleteSQL = "DELETE FROM " + cfg.getTableName()
                + " WHERE " + fixFieldName(cfg.getPrimaryKey()) + " = ?";
        LOG.trace(deleteSQL);
        queryRunner.update(deleteSQL, docId);
    }

    private void sqlInsertDoc(
            String sql, String pkValue,
            List<String> fields, List<String> values) throws SQLException {
        ensureFields(fields);
        var args = new Object[values.size()];
        var i = 0;
        for (String value : values) {
            args[i] = fixFieldValue(fields.get(i), value);
            i++;
        }

        // If it already exists, delete it first.
        if (recordExists(pkValue)) {
            LOG.debug("Record exists. Deleting it first ({}).", pkValue);
            runDelete(pkValue);
        }
        queryRunner.update(sql, args);
    }

    private String fixFieldName(String fieldName) {
        if (!cfg.isFixFieldNames()) {
            return fieldName;
        }
        var newName = fieldName.replaceAll("\\W+", "_");
        newName = newName.replaceFirst("^[\\d_]+", "");
        if (LOG.isDebugEnabled() && !newName.equals(fieldName)) {
            LOG.debug("Field name modified: {} -> {}", fieldName, newName);
        }
        return newName;
    }

    private String interpolate(String text, String fieldName) {
        Map<String, String> vars = new HashMap<>();
        vars.put("tableName", cfg.getTableName());
        vars.put("primaryKey", cfg.getPrimaryKey());
        if (StringUtils.isNotBlank(fieldName)) {
            vars.put("fieldName", fieldName);
        }
        return StringSubstitutor.replace(text, vars, "{", "}");
    }

    private boolean isTargetFieldAlreadySet(
            CommitterRequest req, String refOrContent, String field) {
        var vals = req.getMetadata().getStrings(field);
        if (!vals.isEmpty() && LOG.isDebugEnabled()) {
            LOG.debug(
                    "Target {} field \"{}\" is already set. Document {} will "
                            + "be ignored. Existing value(s): {}.",
                    refOrContent, field, refOrContent, toLogMsg(vals));
        }
        return !vals.isEmpty();
    }

    private String toLogMsg(List<String> values) {
        var val = "\"" + StringUtils.join(values, "\", \"") + "\"";
        if (val.length() > 512) {
            val = StringUtils.truncate(val, 512) + "[...truncated]";
        }
        return val;
    }

    private synchronized void ensureFields(List<String> fields)
            throws SQLException {
        // If not SQL to create field,  we assume they should all exist.
        if (StringUtils.isBlank(cfg.getCreateFieldSQL())) {
            return;
        }

        var currentFields = existingFields.keySet();
        var hasNew = false;
        for (String field : fields) {
            if (!currentFields.contains(
                    StringUtils.lowerCase(field, Locale.ENGLISH))) {
                // Create field
                createField(field);
                hasNew = true;
            }
        }

        // Update fields metadata
        if (hasNew) {
            loadFieldsMetadata();
        }
    }

    private void createField(String field) throws SQLException {
        try {
            var sql = interpolate(cfg.getCreateFieldSQL(), field);
            LOG.trace(sql);
            queryRunner.update(sql);
            LOG.info("New field \"{}\" created.", field);
        } catch (SQLException e) {
            LOG.info("New field \"{}\" could not be created.", field);
            throw e;
        }
    }

    private String fixFieldValue(String fieldName, String value) {
        if (!cfg.isFixFieldValues()) {
            return value;
        }
        var size = existingFields.get(
                StringUtils.lowerCase(fieldName, Locale.ENGLISH));
        if (size == null) {
            return value;
        }
        var newValue = StringUtils.truncate(value, size);
        if (LOG.isDebugEnabled() && !newValue.equals(value)) {
            LOG.debug("Value truncated: {} -> {}", value, newValue);
        }
        return newValue;
    }
}
