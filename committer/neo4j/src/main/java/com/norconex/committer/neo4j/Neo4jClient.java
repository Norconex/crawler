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

import static org.apache.commons.lang3.StringUtils.trimToNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.internal.value.NullValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.CommitterRequest;
import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.commons.lang.encrypt.EncryptionUtil;
import com.norconex.commons.lang.map.Properties;

import lombok.NonNull;

/**
 * <p>
 * Simple Neo4j client.
 * </p>
 */
class Neo4jClient {

    private static final Logger LOG =
            LoggerFactory.getLogger(Neo4jClient.class);

    private final Neo4jCommitterConfig config;

    private final Driver neo4jDriver;
    private final SessionConfig sessionConfig;

    public Neo4jClient(@NonNull Neo4jCommitterConfig config) {
        this.config = config;
        neo4jDriver = createNeo4jDriver();
        sessionConfig = createNeo4jSessionConfig();
    }

    private Driver createNeo4jDriver() {
        Driver driver;
        var creds = config.getCredentials();
        if (creds.isSet()) {
            driver = GraphDatabase.driver(
                    config.getUri(), AuthTokens.basic(
                            creds.getUsername(),
                            EncryptionUtil.decrypt(
                                    creds.getPassword(),
                                    creds.getPasswordKey())));
        } else {
            driver = GraphDatabase.driver(config.getUri());
        }
        LOG.info("Neo4j Driver loaded.");
        return driver;
    }

    private SessionConfig createNeo4jSessionConfig() {
        var b = SessionConfig.builder();
        if (StringUtils.isNotBlank(config.getDatabase())) {
            b.withDatabase(config.getDatabase());
        }
        return b.build();
    }

    public void post(Iterator<CommitterRequest> it) throws CommitterException {
        while (it.hasNext()) {
            try {
                var req = it.next();
                if (req instanceof UpsertRequest upsert) {
                    postUpsert(upsert);
                } else if (req instanceof DeleteRequest delete) {
                    postDelete(delete);
                } else {
                    throw new CommitterException("Unsupported request:" + req);
                }
            } catch (IOException e) {
                throw new CommitterException(
                        "Cannot perform commit request.", e);
            }
        }
    }

    public void close() {
        neo4jDriver.close();
        LOG.info("Neo4j driver closed.");
    }

    private void postUpsert(UpsertRequest req) throws IOException {
        var meta = req.getMetadata();
        if (StringUtils.isNotBlank(config.getNodeIdProperty())) {
            meta.set(config.getNodeIdProperty(), req.getReference());
        }
        if (StringUtils.isNotBlank(config.getNodeContentProperty())) {
            meta.set(
                    config.getNodeContentProperty(), IOUtils.toString(
                            req.getContent(), StandardCharsets.UTF_8));
        }
        try (var session = neo4jDriver.session(sessionConfig)) {
            session.executeWrite(tx -> {
                tx.run(config.getUpsertCypher(), toObjectMap(meta));
                return null;
            });
        }
    }

    private void postDelete(DeleteRequest req) {
        var meta = req.getMetadata();
        Optional.ofNullable(trimToNull(config.getNodeIdProperty())).ifPresent(
                fld -> meta.set(fld, req.getReference()));
        try (var session = neo4jDriver.session(sessionConfig)) {
            session.executeWrite(tx -> {
                tx.run(config.getDeleteCypher(), toObjectMap(meta));
                return null;
            });
        }
    }

    private Map<String, Object> toObjectMap(Properties meta) {
        Map<String, Object> map = new HashMap<>();
        meta.forEach((k, v) -> {
            if (StringUtils.isNotBlank(config.getMultiValuesJoiner())) {
                map.put(k, StringUtils.join(v, config.getMultiValuesJoiner()));
            } else {
                map.put(k, v);
            }
        });

        // Add optional parameters
        config.getOptionalParameters().forEach(
                param -> map.computeIfAbsent(param, p -> NullValue.NULL));
        return map;
    }
}
