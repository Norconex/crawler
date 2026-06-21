/* Copyright 2019-2026 Norconex Inc.
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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.io.IOUtils.toInputStream;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.io.input.NullInputStream;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.internal.types.InternalTypeSystem;
import org.neo4j.driver.types.Node;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

import com.norconex.committer.core.CommitterContext;
import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.commons.lang.TimeIdGenerator;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.security.Credentials;

@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class Neo4jCommitterIT {

    private static final String NEO4J_VERSION = "5.26.0";
    private static final String TEST_CONTENT =
            "This is a movie about something.";

    @SuppressWarnings("resource")
    @Container
    private static Neo4jContainer neo4jContainer = new Neo4jContainer(
            DockerImageName.parse("neo4j").withTag(NEO4J_VERSION))
                    .withoutAuthentication();
    private static Driver driver;
    private static Session session;
    @TempDir
    static File tempDir;

    @BeforeAll
    static void beforeAll() throws Exception {
        driver = GraphDatabase.driver(
                neo4jContainer.getBoltUrl(), AuthTokens.none());
    }

    @BeforeEach
    void beforeEach() throws Exception {
        session = driver.session();
        session.run("""
                MATCH (n)
                DETACH DELETE n""").consume();
    }

    @AfterEach
    void afterEach() throws Exception {
        if (session != null) {
            session.close();
            session = null;
        }
    }

    @AfterAll
    static void afterAll() throws Exception {
        if (driver != null) {
            driver.close();
            driver = null;
        }
    }

    //--- TESTS ----------------------------------------------------------------

    @Test
    void upsertTest() throws CommitterException, IOException {
        commitAllMovies();

        List<Record> records;
        int cnt;

        // Check all movies are there
        records = session.run("""
                 MATCH (movie:Movie)
                RETURN movie.id, movie.title, movie.year""").list();
        cnt = 0;
        for (Record rec : records) {
            var req = movieUpsertRequest(prop(rec, "movie.id"));
            assertThat(meta(req, "title")).isEqualTo(prop(rec, "movie.title"));
            assertThat(meta(req, "year")).isEqualTo(prop(rec, "movie.year"));
            cnt++;
        }
        assertThat(cnt).isEqualTo(3);

        // Check all actors are there
        records = session.run("MATCH (actor:Actor)\nRETURN actor.name").list();
        Set<String> actors = new HashSet<>();
        for (Record rec : records) {
            actors.add(prop(rec, "actor.name"));
        }
        assertThat(actors).containsExactlyInAnyOrder(
                "Keanu Reeves", "Charlize Theron", "Al Pacino",
                "Carrie-Anne Moss", "Laurence Fishburne",
                "Hugo Weaving");

        // Check all producers are there
        records = session.run("""

                MATCH (prod:Producer)
                RETURN prod.name""").list();
        Set<String> producers = new HashSet<>();
        for (Record rec : records) {
            producers.add(prop(rec, "prod.name"));
        }
        assertThat(producers).containsExactly("Joel Silver");

        // Check all directors are there
        records = session.run("""
                MATCH (dir:Director)
                RETURN dir.name""").list();
        Set<String> directors = new HashSet<>();
        for (Record rec : records) {
            directors.add(prop(rec, "dir.name"));
        }
        assertThat(directors).containsExactlyInAnyOrder(
                "Taylor Hackford", "Lilly Wachowski", "Lana Wachowski");
    }

    @Test
    void deleteTest() throws CommitterException, IOException {
        //setup
        var queryFindAllKeanuMovies =
                ("""
                        MATCH (a:Actor WHERE a.name='Keanu Reeves')-[:ACTED_IN]->(m:Movie)
                        WITH m.title as movieTitle
                        RETURN movieTitle""");
        commitAllMovies();

        var records = session.run(queryFindAllKeanuMovies).list();
        assertThat(records).hasSize(3);

        //execute
        withinCommitterSession(c -> {
            var cfg = c.getConfiguration();
            cfg.setDeleteCypher("""
                    MATCH (n:Movie {id: $movieId })
                    DETACH DELETE n
                    """);

            var meta = new Properties();
            meta.add("movieId", "matrix1");

            c.delete(new DeleteRequest("matrix1", meta));
        });

        //verify
        records = session.run(queryFindAllKeanuMovies).list();
        assertThat(records).hasSize(2);
    }

    //--- UTILS. ---------------------------------------------------------------

    private String meta(UpsertRequest req, String key) {
        return req.getMetadata().getString(key);
    }

    private String prop(Record rec, String key) {
        var value = rec.get(key);
        if (value.hasType(InternalTypeSystem.TYPE_SYSTEM.LIST())) {
            return value.asList().get(0).toString();
        }
        return value.asString();
    }

    private void commitAllMovies() throws CommitterException {
        withinCommitterSession(c -> {
            var cfg = c.getConfiguration();
            cfg.addOptionalParameter("producers");
            cfg.setUpsertCypher("""
                    MERGE (m:Movie {
                         id: $movieId, title: $title, year: $year })
                        FOREACH (actor IN COALESCE($actors, []) |
                            MERGE (a:Actor{name: actor})
                            CREATE (a)-[:ACTED_IN]->(m))
                        FOREACH (producer IN COALESCE($producers, []) |
                            MERGE (p:Producer{name: producer})
                            CREATE (p)-[:PRODUCED]->(m))
                        FOREACH (director IN COALESCE($directors, []) |
                            MERGE (d:Director{name: director})
                            CREATE (d)-[:DIRECTED]->(m))""");
            c.upsert(movieUpsertRequest("matrix1"));
            c.upsert(movieUpsertRequest("matrix2"));
            c.upsert(movieUpsertRequest("devilsAdvocate"));
        });
    }

    private UpsertRequest movieUpsertRequest(String movieId)
            throws IOException {
        var meta = new Properties();
        meta.loadFromProperties(
                getClass().getResourceAsStream(
                        "/movies/" + movieId + ".properties"),
                ", ");
        return upsertRequest(movieId, TEST_CONTENT, meta);
    }

    private UpsertRequest upsertRequest(
            String id, String content, Properties metadata) {
        var p = metadata == null ? new Properties() : metadata;
        return new UpsertRequest(
                id, p, content == null
                        ? new NullInputStream(0)
                        : toInputStream(content, UTF_8));
    }

    protected Neo4jCommitter createNeo4jCommitter() throws CommitterException {
        var ctx = CommitterContext.builder()
                .setWorkDir(
                        new File(
                                tempDir,
                                "" + TimeIdGenerator.next()).toPath())
                .build();
        var committer = new Neo4jCommitter();
        var cfg = committer.getConfiguration();
        cfg.setUri(neo4jContainer.getBoltUrl());
        cfg.setNodeIdProperty("movieId");
        cfg.setNodeContentProperty("movieContent");
        cfg.setCredentials(new Credentials("Capitan", "America"));
        committer.init(ctx);
        return committer;
    }

    private Neo4jCommitter withinCommitterSession(CommitterConsumer c)
            throws CommitterException {
        var committer = createNeo4jCommitter();
        try {
            c.accept(committer);
        } catch (CommitterException e) {
            throw e;
        } catch (Exception e) {
            throw new CommitterException(e);
        }
        committer.close();
        return committer;
    }

    @FunctionalInterface
    protected interface CommitterConsumer {
        void accept(Neo4jCommitter c) throws Exception;
    }

    class Utils {
        // will consume result and will no longer be usable
        private Result getAllRecords() {
            return session.run("""
                    MATCH (n)
                    RETURN n""");
        }

        void renderAll() {
            renderResult(getAllRecords());
        }

        private void renderResult(Result result) {
            var records = result.list();
            System.out.println("=== DUMP: ======================");
            for (Record rec : records) {
                renderMap(0, rec.asMap());
            }
        }

        public void renderMap(int depth, Map<String, Object> map) {
            for (Entry<String, Object> en : map.entrySet()) {
                var key = en.getKey();
                var value = en.getValue();
                var indent = StringUtils.repeat(' ', depth * 2);
                if (value instanceof Node node) {
                    System.out.print(indent + key);
                    node.labels().forEach(l -> System.out.print(":" + l));
                    System.out.println(" {");
                    renderMap(depth + 1, node.asMap());
                    System.out.println(indent + "}");
                } else if (value instanceof Collection) {
                    System.out.print(indent + key);
                    ((Collection<?>) value)
                            .forEach(v -> System.out.print(":" + v));
                    System.out.println();
                } else {
                    System.out.println(indent + key + ": " + value);
                }
            }
        }
    }
}
