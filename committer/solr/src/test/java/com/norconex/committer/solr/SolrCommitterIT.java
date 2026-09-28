/* Copyright 2010-2026 Norconex Inc.
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
package com.norconex.committer.solr;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.io.IOUtils.toInputStream;

import java.io.IOException;

import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.commons.lang.map.Properties;

/**
 * SolrCommitter main tests.

 */
@Timeout(30)
class SolrCommitterIT extends AbstractSolrIT {

    //TODO test update/delete URL params

    @Test
    void testCommitAdd() throws Exception {

        // Add new doc to Solr
        withinCommitterSession(c -> {
            c.upsert(upsertRequest("1", "Hello world!"));
        });

        // Check that it's in Solr
        var results = queryId("1");
        Assertions.assertEquals(1, results.getNumFound());
    }

    @Test
    void testAddWithQueueContaining2documents() throws Exception {
        withinCommitterSession(c -> {
            c.upsert(upsertRequest("1", "Document 1"));
            c.upsert(upsertRequest("2", "Document 2"));
        });

        //Check that there is 2 documents in Solr
        var results = getAllDocs();
        Assertions.assertEquals(2, results.getNumFound());
    }

    @Test
    void testCommitQueueWith3AddCommandAnd1DeleteCommand()
            throws Exception {

        withinCommitterSession(c -> {
            c.upsert(upsertRequest("1", "Document 1"));
            c.upsert(upsertRequest("2", "Document 2"));
            c.delete(new DeleteRequest("1", new Properties()));
            c.upsert(upsertRequest("3", "Document 3"));
        });

        //Check that there is 2 documents in Solr
        var results = getAllDocs();
        Assertions.assertEquals(2, results.getNumFound());
    }

    @Test
    void testCommitQueueWith3AddCommandAnd2DeleteCommand()
            throws Exception {

        withinCommitterSession(c -> {
            c.upsert(upsertRequest("1", "Document 1"));
            c.upsert(upsertRequest("2", "Document 2"));
            c.delete(new DeleteRequest("1", new Properties()));
            c.delete(new DeleteRequest("2", new Properties()));
            c.upsert(upsertRequest("3", "Document 3"));
        });

        //Check that there is 2 documents in Solr
        var results = getAllDocs();
        Assertions.assertEquals(1, results.getNumFound());
    }

    @Test
    void testCommitDelete() throws Exception {

        // Add a document directly to Solr
        var doc = new SolrInputDocument();
        doc.addField(SolrCommitterConfig.DEFAULT_SOLR_ID_FIELD, "1");
        doc.addField(
                SolrCommitterConfig.DEFAULT_SOLR_CONTENT_FIELD,
                "Hello world!");
        getSolrClient().add(doc);
        getSolrClient().commit();

        withinCommitterSession(c -> {
            c.delete(new DeleteRequest("1", new Properties()));
        });

        // Check that it's remove from Solr
        var results = queryId("1");
        Assertions.assertEquals(0, results.getNumFound());
    }

    //TODO test source + target mappings + other mappings

    private SolrDocumentList queryId(String id)
            throws SolrServerException, IOException {
        var solrParams = new ModifiableSolrParams();
        solrParams.set(
                "q", String.format(
                        "%s:%s",
                        SolrCommitterConfig.DEFAULT_SOLR_ID_FIELD, id));
        var response = getSolrClient().query(solrParams);
        return response.getResults();
    }

    private SolrDocumentList getAllDocs()
            throws SolrServerException, IOException {
        var solrParams = new ModifiableSolrParams();
        solrParams.set("q", "*:*");
        var response = getSolrClient().query(solrParams);
        return response.getResults();
    }

    private UpsertRequest upsertRequest(String id, String content) {
        var p = new Properties();
        p.add("id", id);
        return new UpsertRequest(id, p, toInputStream(content, UTF_8));
    }
}
