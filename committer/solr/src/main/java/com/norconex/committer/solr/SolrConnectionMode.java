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
package com.norconex.committer.solr;

/**
 * How the Solr committer connects to the configured Solr nodes.
 */
public enum SolrConnectionMode {

    /**
     * A single Solr URL.
     */
    SINGLE_NODE,

    /**
     * Several Solr node URLs. Updates are spread across nodes, and a failing
     * node is set aside for a while, the request being sent to another one.
     */
    LOAD_BALANCED,

    /**
     * Solr node URLs of a SolrCloud cluster, each ending with the collection
     * name. The nodes hosting the collection are discovered over HTTP
     * (Collections API <code>CLUSTERSTATUS</code>) and refreshed
     * periodically. ZooKeeper does not have to be reachable.
     * If the cluster state cannot be obtained, or discovered nodes are not
     * reachable, the configured nodes are used.
     */
    CLOUD
}
