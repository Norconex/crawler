/* Copyright 2017-2026 Norconex Inc.
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

import java.util.Iterator;

import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.CommitterRequest;
import com.norconex.committer.core.batch.AbstractBatchCommitter;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * <p>
 * Commit documents to an SQL table. Document metadata fields
 * are mapped to table columns.
 * </p>
 *
 * <h2>Handling of missing table/fields</h2>
 * <p>
 * By default, this Committer will throw an exception when trying to insert
 * values into non-existing database table or fields. It is recommended you
 * make sure your database table exists and the document fields being sent
 * to the committer match your database fields.
 * </p>
 * <p>
 * Alternatively, you can provide the necessary SQLs to create a new
 * table as well as new fields as needed using
 * {@link SqlCommitterConfig#setCreateTableSQL(String)} and
 * {@link SqlCommitterConfig#setCreateFieldSQL(String)}
 * respectively. Make sure to use the following placeholder variables
 * as needed in the provided SQL(s) table and field creation, respectively:
 * </p>
 *
 * <h3>Table creation</h3>
 * <dl>
 *   <dt>{tableName}</dt>
 *   <dd>
 *     Your table name, to be replaced with the value supplied with
 *     {@link SqlCommitterConfig#setTableName(String)}.
 *   </dd>
 *   <dt>{primaryKey}</dt>
 *   <dd>
 *     Your table primary key field name, to be replaced with the value
 *     supplied with {@link SqlCommitterConfig#setPrimaryKey(String)}.
 *   </dd>
 * </dl>
 * <h3>Field creation</h3>
 * <dl>
 *   <dt>{fieldName}</dt>
 *   <dd>
 *     A field name to be created if you provided an SQL for creating new
 *     fields.
 *   </dd>
 * </dl>

 */
@EqualsAndHashCode
@ToString
public class SqlCommitter extends AbstractBatchCommitter<SqlCommitterConfig> {

    @Getter
    private final SqlCommitterConfig configuration = new SqlCommitterConfig();

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private SqlClient client;

    @Override
    protected void initBatchCommitter() throws CommitterException {
        client = new SqlClient(configuration);
    }

    @Override
    protected void commitBatch(Iterator<CommitterRequest> it)
            throws CommitterException {
        client.post(it);
    }

    @Override
    protected void closeBatchCommitter() throws CommitterException {
        if (client != null) {
            client.close();
        }
        client = null;
    }
}
