/* Copyright 2013-2026 Norconex Inc.
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
package com.norconex.committer.elasticsearch;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.Node;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClient.FailureListener;
import org.elasticsearch.client.sniff.ElasticsearchNodesSniffer;
import org.elasticsearch.client.sniff.NodesSniffer;
import org.elasticsearch.client.sniff.Sniffer;
import org.json.JSONObject;

import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.CommitterRequest;
import com.norconex.committer.core.CommitterUtil;
import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.committer.core.batch.AbstractBatchCommitter;
import com.norconex.commons.lang.encrypt.EncryptionUtil;
import com.norconex.commons.lang.io.IoUtil;
import com.norconex.commons.lang.text.StringUtil;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Commits documents to Elasticsearch. This committer
 * relies on Elasticsearch REST API.
 * </p>
 *
 * <h2>"_id" field</h2>
 * <p>
 * Elasticsearch expects a field named "_id" that uniquely identifies
 * each documents.  You can provide that field yourself in documents
 * you submit.  If you do not specify an "_id" field, this committer
 * will create one for you, using the document reference as the identifier
 * value.
 * </p>
 *
 * <h2>"content" field</h2>
 * <p>
 * By default the "body" of a document is read as an input stream
 * and stored in a "content" field.  You can change that target field name
 * with {@link ElasticsearchCommitterConfig#setTargetContentField(String)}.
 * If you set the target content field to {@code null}, it will
 * effectively skip storing the content stream.
 * </p>
 *
 * <h2>Dots (.) in field names</h2>
 * <p>
 * Your Elasticsearch installation may consider dots in field names
 * to be representing "objects", which may not always be what you want.
 * If having dots is causing you issues, make sure not to submit fields
 * with dots, or use
 * {@link ElasticsearchCommitterConfig#setDotReplacement(String)} to replace
 * dots with a character of your choice (e.g., underscore).
 * If your dot represents a nested object, keep reading.
 * </p>
 *
 * <h2>JSON Objects</h2>
 * <p>
 * It is possible to provide a regular expression
 * that will identify one or more fields containing a JSON object rather
 * than a regular string
 * ({@link ElasticsearchCommitterConfig#setJsonFieldsPattern(String)}).
 * For example, this is a useful way to store nested objects. While very
 * flexible, it can be challenging to come up with the JSON structure. You may
 * want to consider custom code.
 * For this to work properly, make sure you define your Elasticsearch
 * field mappings on your index beforehand.
 * </p>
 *
 * <h2>Elasticsearch ID limitations:</h2>
 * <p>
 * As of this writing, Elasticsearch 5 or higher have a 512 bytes
 * limitation on its "_id" field.
 * By default, an error (from Elasticsearch) will result from trying to submit
 * documents with an invalid ID. You can get around this by
 * setting {@link ElasticsearchCommitterConfig#setFixBadIds(boolean)} to
 * <code>true</code>.  It will truncate references that are too long and
 * append a hash code to it
 * representing the truncated part. This approach is not 100%
 * collision-free (uniqueness), but it should safely cover the vast
 * majority of cases.
 * </p>
 *
 * <h2>Type Name</h2>
 * <p>
 * As of Elasticsearch 7.0, the index type has been deprecated.
 * If you are using Elasticsearch 7.0 or higher, do not configure the
 * <code>typeName</code>. Doing so may cause errors.
 * The <code>typeName</code> is available only for backward compatibility
 * for those using this Committer with older versions of Elasticsearch.
 * </p>
 *
 * <h2>Authentication</h2>
 * <p>
 * Basic authentication is supported for password-protected clusters.
 * Alternatively, API Key authentication can be used by providing the
 * encoded API key value via
 * {@link ElasticsearchCommitterConfig#setApiKey(String)}.
 * When an API key is set, it takes precedence over basic credentials.
 * The API key value should be the Base64-encoded string as provided
 * by Elasticsearch (i.e., the value sent in the
 * {@code Authorization: ApiKey ...} header).
 * </p>
 *
 * <h2>Timeouts</h2>
 * <p>
 * You can specify timeout values for when this committer sends documents
 * to Elasticsearch.
 * </p>

 */
@EqualsAndHashCode
@ToString
@Slf4j
public class ElasticsearchCommitter
        extends AbstractBatchCommitter<ElasticsearchCommitterConfig> {

    public static final String ELASTICSEARCH_ID_FIELD = "_id";

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private RestClient client;
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private Sniffer sniffer;

    @Getter
    private final ElasticsearchCommitterConfig configuration =
            new ElasticsearchCommitterConfig();

    @Override
    protected void initBatchCommitter() throws CommitterException {
        if (StringUtils.isBlank(configuration.getIndexName())) {
            throw new CommitterException("Index name is undefined.");
        }
        client = createRestClient();
        if (configuration.isDiscoverNodes()) {
            sniffer = createSniffer(client);
        }
    }

    private String extractId(CommitterRequest req) throws CommitterException {
        return fixBadIdValue(CommitterUtil.extractSourceIdValue(
                req, configuration.getSourceIdField()));
    }

    @Override
    protected void commitBatch(Iterator<CommitterRequest> it)
            throws CommitterException {

        var json = new StringBuilder();

        var docCount = 0;
        try {
            while (it.hasNext()) {
                var req = it.next();
                if (req instanceof UpsertRequest upsert) {
                    appendUpsertRequest(json, upsert);
                } else if (req instanceof DeleteRequest delete) {
                    appendDeleteRequest(json, delete);
                } else {
                    throw new CommitterException("Unsupported request: " + req);
                }
                docCount++;
            }
            if (LOG.isTraceEnabled()) {
                LOG.trace("JSON POST:\n{}",
                        StringUtils.trim(json.toString()));
            }

            var request = new Request("POST", "/_bulk");
            request.setJsonEntity(json.toString());
            var response = client.performRequest(request);
            handleResponse(response);
            LOG.info("Sent {} commit operations to Elasticsearch.", docCount);
        } catch (CommitterException e) {
            throw e;
        } catch (Exception e) {
            throw new CommitterException(
                    "Could not commit JSON batch to Elasticsearch.", e);
        }
    }

    @Override
    protected void closeBatchCommitter() throws CommitterException {
        IoUtil.closeQuietly(sniffer);
        IoUtil.closeQuietly(client);
        client = null;
        sniffer = null;
        LOG.info("Elasticsearch RestClient closed.");
    }

    private void handleResponse(Response response)
            throws IOException, CommitterException {
        var respEntity = response.getEntity();
        if (respEntity != null) {
            var responseAsString = IOUtils.toString(
                    respEntity.getContent(), StandardCharsets.UTF_8);
            if (LOG.isTraceEnabled()) {
                LOG.trace("Elasticsearch response:\n{}",
                        responseAsString);
            }

            // We have no need to parse the JSON if successful
            // (saving on the parsing). We'll do it on errors only
            // to filter out successful ones and report only the errors
            if (StringUtils.substring(
                    responseAsString, 0, 100).contains("\"errors\":true")) {
                var error = extractResponseErrors(responseAsString);
                if (!configuration.isIgnoreResponseErrors()) {
                    throw new CommitterException(error);
                }
                LOG.error(error);
            }
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Elasticsearch response status: {}",
                    response.getStatusLine());
        }
        if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
            throw new CommitterException(
                    "Invalid HTTP response: " + response.getStatusLine());
        }
    }

    private String extractResponseErrors(String response) {
        var error = new StringBuilder();
        var json = new JSONObject(response);
        var items = json.getJSONArray("items");

        for (var i = 0; i < items.length(); i++) {
            var index = items.getJSONObject(i).getJSONObject("index");
            if (index.has("error")) {
                if (error.length() > 0) {
                    error.append(",\n");
                }
                error.append(index.toString(4));
            }
        }
        error.append(']');
        error.insert(0, "Elasticsearch returned one or more errors:\n[");
        return error.toString();
    }

    private void appendUpsertRequest(StringBuilder json, UpsertRequest req)
            throws CommitterException {

        CommitterUtil.applyTargetContent(
                req, configuration.getTargetContentField());

        json.append("{\"index\":{");
        append(json, "_index", configuration.getIndexName());
        if (StringUtils.isNotBlank(configuration.getTypeName())) {
            append(json.append(','), "_type", configuration.getTypeName());
        }
        append(json.append(','), ELASTICSEARCH_ID_FIELD, extractId(req));
        json.append("}}\n{");
        var first = true;
        for (Entry<String, List<String>> entry : req.getMetadata().entrySet()) {
            var field = entry.getKey();
            field = Strings.CS.replace(
                    field, ".",
                    configuration.getDotReplacement());
            // Do not store _id as a field since it is passed above already.
            if (ELASTICSEARCH_ID_FIELD.equals(field)) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            append(json, field, entry.getValue());
            first = false;
        }
        json.append("}\n");
    }

    private void appendDeleteRequest(StringBuilder json, DeleteRequest req)
            throws CommitterException {
        json.append("{\"delete\":{");
        append(json, "_index", configuration.getIndexName());
        if (StringUtils.isNotBlank(configuration.getTypeName())) {
            append(json.append(','), "_type", configuration.getTypeName());
        }
        append(json.append(','), ELASTICSEARCH_ID_FIELD, extractId(req));
        json.append("}}\n");
    }

    private void append(
            StringBuilder json, String field, List<String> values) {
        if (values.size() == 1) {
            append(json, field, values.get(0));
            return;
        }

        json.append('"')
                .append(StringEscapeUtils.escapeJson(field))
                .append("\":[");

        for (String value : values) {
            appendValue(json, field, value);
            json.append(',');
        }

        json.deleteCharAt(json.length() - 1);
        json.append(']');
    }

    private void append(StringBuilder json, String field, String value) {
        json.append('"')
                .append(StringEscapeUtils.escapeJson(field))
                .append("\":");
        appendValue(json, field, value);
    }

    private void appendValue(StringBuilder json, String field, String value) {
        if (configuration.getJsonFieldsPattern() != null
                && configuration.getJsonFieldsPattern().matches(field)) {
            json.append(value);
        } else {
            json.append('"')
                    .append(StringEscapeUtils.escapeJson(value))
                    .append("\"");
        }
    }

    private String fixBadIdValue(String value) throws CommitterException {
        if (StringUtils.isBlank(value)) {
            throw new CommitterException("Document id cannot be empty.");
        }
        if (configuration.isFixBadIds()
                && value.getBytes(StandardCharsets.UTF_8).length > 512) {
            String v;
            try {
                v = StringUtil.truncateBytesWithHash(
                        value, StandardCharsets.UTF_8, 512, "!");
            } catch (CharacterCodingException e) {
                LOG.error("""
                        Bad id detected (too long), but could not be\s\
                        truncated properly by byte size. Will truncate\s\
                        based on characters size instead, which may not\s\
                        work on IDs containing multi-byte characters.""");
                v = StringUtil.truncateWithHash(value, 512, "!");
            }
            if (LOG.isDebugEnabled() && !value.equals(v)) {
                LOG.debug("Fixed document id from \"{}\" to \"{}\".", value, v);
            }
            return v;
        }
        return value;
    }

    protected RestClient createRestClient() {
        var elasticHosts = configuration.getNodes();
        var httpHosts = new HttpHost[elasticHosts.size()];
        for (var i = 0; i < elasticHosts.size(); i++) {
            httpHosts[i] = HttpHost.create(elasticHosts.get(i));
        }

        var builder = RestClient.builder(httpHosts);
        builder.setFailureListener(new FailureListener() {
            @Override
            public void onFailure(Node node) {
                LOG.error("Failure occured on node: \"{}\". Check node logs.",
                        node.getName());
            }
        });
        builder.setRequestConfigCallback(rcb -> rcb.setConnectTimeout(
                (int) configuration.getConnectionTimeout().toMillis())
                .setSocketTimeout(
                        (int) configuration.getSocketTimeout().toMillis()));

        var apiKey = configuration.getApiKey();
        var credentials = configuration.getCredentials();
        if (StringUtils.isNotBlank(apiKey)) {
            Header authHeader = new BasicHeader(
                    "Authorization", "ApiKey " + apiKey);
            builder.setDefaultHeaders(new Header[] { authHeader });
        } else if (credentials.isSet()) {
            CredentialsProvider credsProvider = new BasicCredentialsProvider();
            credsProvider.setCredentials(
                    AuthScope.ANY, new UsernamePasswordCredentials(
                            credentials.getUsername(), EncryptionUtil.decrypt(
                                    credentials.getPassword(),
                                    credentials.getPasswordKey())));
            builder.setHttpClientConfigCallback(
                    b -> b.setDefaultCredentialsProvider(credsProvider));
        }
        return builder.build();
    }

    protected Sniffer createSniffer(RestClient client) {
        // here we assume a cluster is either all https, or all https (no mix).
        if (!configuration.getNodes().isEmpty()
                && configuration.getNodes().get(0).startsWith("https:")) {
            NodesSniffer nodesSniffer = new ElasticsearchNodesSniffer(
                    client,
                    ElasticsearchNodesSniffer.DEFAULT_SNIFF_REQUEST_TIMEOUT,
                    ElasticsearchNodesSniffer.Scheme.HTTPS);
            return Sniffer.builder(
                    client).setNodesSniffer(nodesSniffer).build();
        }
        return Sniffer.builder(client).build();
    }
}
