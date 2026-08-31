/* Copyright 2019-2024 Norconex Inc.
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
package com.norconex.committer.neo4j;

import java.util.Iterator;

import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.CommitterRequest;
import com.norconex.committer.core.batch.AbstractBatchCommitter;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * <p>
 * Commit documents/fields to a Neo4j graph database.
 * </p>
 *
 * <h2>Authentication</h2>
 * <p>
 * Basic authentication is supported for password-protected
 * Solr installations.
 * </p>

 */
@EqualsAndHashCode
@ToString
public class Neo4jCommitter
        extends AbstractBatchCommitter<Neo4jCommitterConfig> {

    @Getter
    private final Neo4jCommitterConfig configuration =
            new Neo4jCommitterConfig();

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private Neo4jClient client;

    @Override
    protected void initBatchCommitter() throws CommitterException {
        client = new Neo4jClient(configuration);
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
    }
}
