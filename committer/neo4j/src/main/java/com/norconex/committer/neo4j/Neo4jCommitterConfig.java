/* Copyright 2021-2024 Norconex Inc.
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

import java.io.Serializable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.neo4j.driver.internal.value.NullValue;

import com.norconex.committer.core.batch.BaseBatchCommitterConfig;
import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.commons.lang.security.Credentials;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * Neo4j Committer configuration.
 * </p>
 * @author Sylvain Roussy
 * @author Pascal Essiembre
 */
@Data
@Accessors(chain = true)
public class Neo4jCommitterConfig
        extends BaseBatchCommitterConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String DEFAULT_NEO4J_ID_PROPERTY = "id";
    public static final String DEFAULT_NEO4J_CONTENT_PROPERTY = "content";

    /**
     * Required connection URI. E.g., "bolt://localhost:7687".
     */
    private String uri;
    /**
     * A database name when using one other than the default one.
     */
    private String database;
    /**
     * Connection credentials.
     */
    private final Credentials credentials = new Credentials();
    /**
     * One or more characters to join multi-value fields. Default is "|".
     */
    private String multiValuesJoiner;
    /**
     * Optional property name where to store the document reference
     * in Neo4j graph entries.  Use it as a cypher parameter to uniquely
     * identify your graph entries in your configured "upsertCypher" and
     * "deleteCypher" queries. Default is "id".
     */
    private String nodeIdProperty = DEFAULT_NEO4J_ID_PROPERTY;
    /**
     * Optional property name where to store the document content
     * in Neo4j graph entries.  Use it as a cypher parameter
     * in your configured "upsertCypher" query. Default is "content".
     */
    private String nodeContentProperty = DEFAULT_NEO4J_CONTENT_PROPERTY;
    /**
     * Cypher query for adding relationships to Neo4j. Typically,
     * you want to use MERGE and the "nodeIdProperty" value to update
     * existing entries matching the ID. In order to delete all nodes
     * related to an entry, make sure to add the ID property on all
     * appropriate nodes. The query parameters correspond the document fields.
     */
    private String upsertCypher;
    /**
     * Cypher query for adding relationships to Neo4j. Typically,
     * you want to use "nodeIdProperty" value to delete nodes having
     * a matching property value. The query parameters correspond the
     * document fields.
     */
    private String deleteCypher;
    /**
     * Comma-separated list of parameter names that can be missing when
     * creating the query. They will be set to {@link NullValue}) to avoid
     * client exception for missing parameters.
     */
    private final Set<String> optionalParameters = new HashSet<>();

    public void setCredentials(Credentials credentials) {
        this.credentials.copyFrom(credentials);
    }

    public Set<String> getOptionalParameters() {
        return Collections.unmodifiableSet(optionalParameters);
    }

    public void setOptionalParameters(Set<String> optionalParameters) {
        CollectionUtil.setAll(this.optionalParameters, optionalParameters);
    }

    public void addOptionalParameter(String optionalParameter) {
        optionalParameters.add(optionalParameter);
    }
}
