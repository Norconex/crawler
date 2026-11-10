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
package com.norconex.committer.solr;

import java.util.Iterator;
import java.util.LinkedHashMap;

import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.CommitterRequest;
import com.norconex.committer.core.CommitterUtil;
import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.committer.core.batch.AbstractBatchCommitter;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Commits documents to Apache Solr (and Solr-based products, such as
 * LucidWorks Fusion). It sends JSON update requests over HTTP and does
 * not depend on the SolrJ library.
 * </p>
 *
 * <h2>Connection type</h2>
 * <p>
 * The <code>solrClientType</code> influences what the expected
 * <code>solrURL</code> value is. All URLs are Solr "core" or "collection"
 * URLs, such as <code>http://localhost:8983/solr/mycollection</code>.
 * Client types keep the name of the SolrJ client they replace so existing
 * configurations remain valid. They are:
 * </p>
 * <dl>
 *   <dt>HttpJettySolrClient</dt>
 *     <dd>Default. For direct access to a single Solr node. Expects a
 *         Solr URL. HTTP/2 is used when the URL starts with
 *         <code>https</code>, else HTTP/1.1 is used.</dd>
 *   <dt>CloudSolrClient</dt>
 *     <dd>For use with a SolrCloud cluster. Expects one or more Solr node
 *         URLs (comma-separated), each ending with the collection name.
 *         The nodes currently hosting the collection are discovered
 *         over HTTP (Collections API <code>CLUSTERSTATUS</code>) and
 *         refreshed periodically, so ZooKeeper does not have to be
 *         reachable. Updates are spread across these nodes. If the cluster
 *         state cannot be obtained (e.g., missing permission) or
 *         discovered nodes are unreachable, the configured URLs are used.
 *         <b>ZooKeeper connection strings are not supported.</b></dd>
 *   <dt>LBJettySolrClient</dt>
 *     <dd>Simple load-balancing as an alternative to an external load
 *         balancer. Expects two or more Solr node URLs (comma-separated).
 *         Updates are spread across nodes. A failing node is set aside
 *         for a while and the request is sent to another one.</dd>
 *   <dt>ConcurrentUpdateJettySolrClient, HttpJdkSolrClient</dt>
 *     <dd>Same as <code>HttpJettySolrClient</code> (documents are already
 *         sent in batches).</dd>
 *   <dt>HttpSolrClient, LBHttpSolrClient, ConcurrentUpdateSolrClient</dt>
 *     <dd><b>Deprecated.</b> Same as their HTTP/2 counterparts, but always
 *         using HTTP/1.1.</dd>
 * </dl>
 *
 * <h2>Authentication</h2>
 * <p>
 * The following are supported, and sent with every request to Solr:
 * </p>
 * <ul>
 *   <li><b>Basic authentication</b>: with <code>credentials</code>
 *       (password can be encrypted).</li>
 *   <li><b>Bearer token</b>: with <code>bearerToken</code>
 *       (e.g., for the Solr JWT authentication plugin).
 *       Ignored when basic credentials are set.</li>
 *   <li><b>Custom HTTP headers</b>: with <code>headers</code> (e.g., for an
 *       API gateway or a reverse proxy). These can override the
 *       <code>Authorization</code> header set by the above.</li>
 *   <li><b>TLS</b>: for HTTPS, the JVM default trust store is used
 *       (customizable with the standard <code>javax.net.ssl.*</code>
 *       system properties, including for client certificates).</li>
 * </ul>
 * <p>
 * HTTP proxies are those of the JVM (standard <code>http.proxyHost</code>
 * and <code>https.proxyHost</code> system properties).
 * Kerberos authentication is not supported.
 * </p>
 */
@EqualsAndHashCode
@ToString
@Slf4j
public class SolrCommitter
        extends AbstractBatchCommitter<SolrCommitterConfig> {

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @Getter(value = AccessLevel.NONE)
    @Setter(value = AccessLevel.NONE)
    private SolrHttpClient solrClient;

    @Getter
    private final SolrCommitterConfig configuration =
            new SolrCommitterConfig();

    @Override
    protected void initBatchCommitter() throws CommitterException {
        solrClient = new SolrHttpClient(configuration);
    }

    @Override
    protected void commitBatch(Iterator<CommitterRequest> it)
            throws CommitterException {

        // Operations are sent in a single request, in the order received.
        // Solr executes them in that order, so a delete following the
        // addition of the same document is honored.
        var batch = new SolrUpdateBatch();
        try {
            while (it.hasNext()) {
                var r = it.next();
                if (r instanceof UpsertRequest upsert) {
                    addUpsert(batch, upsert);
                } else if (r instanceof DeleteRequest delete) {
                    addDelete(batch, delete);
                } else {
                    throw new CommitterException("Unsupported operation:" + r);
                }
            }
            if (batch.isEmpty()) {
                return;
            }
            push(batch);
            LOG.info("Sent {} committer operations to Solr.", batch.size());
        } catch (CommitterException e) {
            throw e;
        } catch (Exception e) {
            throw new CommitterException(
                    "Cannot push document batch to Solr.", e);
        }
    }

    @Override
    protected void closeBatchCommitter() throws CommitterException {
        if (solrClient != null) {
            solrClient.close();
            solrClient = null;
        }
        LOG.info("Solr client closed.");
    }

    private void push(SolrUpdateBatch batch) throws CommitterException {
        var params = new LinkedHashMap<String, String>();
        // Explicit commit at the end of every batch, unless disabled.
        if (!configuration.isSolrCommitDisabled()) {
            params.put("commit", "true");
        }
        // user-defined parameters can override the above
        params.putAll(configuration.getUpdateUrlParams());
        solrClient.update(batch.toBytes(), params);
    }

    private void addUpsert(SolrUpdateBatch batch, UpsertRequest request)
            throws CommitterException {
        CommitterUtil.applyTargetId(
                request,
                configuration.getSourceIdField(),
                configuration.getTargetIdField());
        CommitterUtil.applyTargetContent(
                request, configuration.getTargetContentField());
        batch.add(request.getMetadata());
    }

    private void addDelete(SolrUpdateBatch batch, DeleteRequest request) {
        CommitterUtil.applyTargetId(
                request,
                configuration.getSourceIdField(),
                configuration.getTargetIdField());
        batch.delete(request.getReference());
    }
}
