/* Copyright 2016-2026 Norconex Inc.
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
package com.norconex.committer.amazoncloudsearch;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.cloudsearchdomain.AmazonCloudSearchDomain;
import com.amazonaws.services.cloudsearchdomain.AmazonCloudSearchDomainClientBuilder;
import com.amazonaws.services.cloudsearchdomain.model.UploadDocumentsRequest;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.CommitterRequest;
import com.norconex.committer.core.CommitterUtil;
import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.committer.core.batch.AbstractBatchCommitter;
import com.norconex.commons.lang.encrypt.EncryptionUtil;
import com.norconex.commons.lang.text.StringUtil;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Commits documents to Amazon CloudSearch.
 * </p>
 * <h2>Authentication:</h2>
 * <p>
 * An access key and security key are required to connect to and interact with
 * CloudSearch. For enhanced security, it is best to use one of the methods
 * described in {@link DefaultAWSCredentialsProviderChain} for setting them
 * (environment variables, system properties, profile file, etc).
 * Do not explicitly set "accessKey" and "secretKey" on this class if you
 * want to rely on safer methods.
 * </p>
 * <h2>CloudSearch ID limitations:</h2>
 * <p>
 * As of this writing, CloudSearch has a 128 characters length limitation
 * on its "id" field. In addition, certain characters are not allowed.
 * By default, an error will result from trying to submit
 * documents with an invalid ID. You can get around this by
 * setting {@link AmazonCloudSearchCommitterConfig#setFixBadIds(boolean)}
 * to <code>true</code>.  It will
 * truncate references that are too long and append a hash code to it
 * to keep uniqueness.  It will also convert invalid
 * characters to underscore.  This approach is not 100%
 * collision-free (uniqueness), but it should safely cover the vast
 * majority of cases.
 * </p>
 */
@EqualsAndHashCode
@ToString
@Slf4j
public class AmazonCloudSearchCommitter
        extends AbstractBatchCommitter<AmazonCloudSearchCommitterConfig> {

    private static final String AWS_V1_DISABLE_ANNOUNCEMENT_PROPERTY =
            "aws.java.v1.disableDeprecationAnnouncement";

    /**
     * CouldSearch mandatory field pattern. Characters not matching
     * the pattern will be replaced by an underscore.
     */
    public static final Pattern FIELD_PATTERN = Pattern.compile(
            "[a-z0-9][a-z0-9_]{0,63}$");

    /** CloudSearch mandatory ID field */
    public static final String COULDSEARCH_ID_FIELD = "id";

    @Getter
    private final AmazonCloudSearchCommitterConfig configuration =
            new AmazonCloudSearchCommitterConfig();

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    private AmazonCloudSearchDomain awsClient;

    @Override
    protected void initBatchCommitter() throws CommitterException {
        System.setProperty(AWS_V1_DISABLE_ANNOUNCEMENT_PROPERTY, "true");
        // Build AWS Client

        if (StringUtils.isBlank(configuration.getServiceEndpoint())) {
            throw new CommitterException("Service endpoint is undefined.");
        }
        var b = AmazonCloudSearchDomainClientBuilder.standard();
        var clientConfig = new ClientConfiguration();
        if (configuration.getProxySettings().isSet()) {
            var proxy = configuration.getProxySettings();
            clientConfig.setProxyHost(proxy.getHost().getName());
            clientConfig.setProxyPort(proxy.getHost().getPort());
            if (proxy.getCredentials().isSet()) {
                clientConfig.setProxyUsername(
                        proxy.getCredentials().getUsername());
                clientConfig.setProxyPassword(
                        EncryptionUtil.decrypt(
                                proxy.getCredentials().getPassword(),
                                proxy.getCredentials().getPasswordKey()));
            }
        }
        b.setClientConfiguration(clientConfig);
        if (StringUtils.isAnyBlank(
                configuration.getAccessKey(),
                configuration.getSecretKey())) {
            b.withCredentials(new DefaultAWSCredentialsProviderChain());
        } else {
            b.withCredentials(
                    new AWSStaticCredentialsProvider(
                            new BasicAWSCredentials(
                                    configuration.getAccessKey(),
                                    configuration.getSecretKey())));
        }
        b.withEndpointConfiguration(
                new EndpointConfiguration(
                        configuration.getServiceEndpoint(),
                        configuration.getSigningRegion()));
        awsClient = b.build();
    }

    @Override
    protected void commitBatch(Iterator<CommitterRequest> it)
            throws CommitterException {

        List<JSONObject> jsonBatch = new ArrayList<>();
        try {
            while (it.hasNext()) {
                var req = it.next();
                if (req instanceof UpsertRequest upsert) {
                    jsonBatch.add(toJsonDocUpsert(upsert));
                } else if (req instanceof DeleteRequest delete) {
                    jsonBatch.add(toJsonDocDelete(delete));
                } else {
                    throw new CommitterException("Unsupported request:" + req);
                }
            }
            uploadBatchToCloudSearch(jsonBatch);
        } catch (CommitterException e) {
            throw e;
        } catch (Exception e) {
            throw new CommitterException(
                    "Could not commit JSON batch to CloudSearch.", e);
        }
    }

    @Override
    protected void closeBatchCommitter() throws CommitterException {
        if (awsClient != null) {
            awsClient.shutdown();
        }
        awsClient = null;
        LOG.info("Amazon Cloud Search client shut down.");
    }

    private void uploadBatchToCloudSearch(List<JSONObject> documentBatch)
            throws CommitterException {
        // Convert the JSON list to String and read it as a stream from memory
        // (for increased performance), for it to be usable by the AWS
        // CloudSearch UploadRequest. If memory becomes a concern, consider
        // streaming to file.
        // ArrayList.toString() joins the elements in a JSON-compliant way.
        var bytes =
                documentBatch.toString().getBytes(StandardCharsets.UTF_8);
        try (var is = new ByteArrayInputStream(bytes)) {
            var uploadRequest = new UploadDocumentsRequest();
            uploadRequest.setContentType("application/json");
            uploadRequest.setDocuments(is);
            uploadRequest.setContentLength((long) bytes.length);
            var result =
                    awsClient.uploadDocuments(uploadRequest);
            LOG.info(
                    "{} upserts and {} deletes sent to the AWS CloudSearch "
                            + "domain.",
                    result.getAdds(), result.getDeletes());
        } catch (IOException | AmazonServiceException e) {
            throw new CommitterException(
                    "Could not execute CloudSearch upload request.", e);
        }
    }

    private JSONObject toJsonDocUpsert(UpsertRequest req)
            throws CommitterException {

        CommitterUtil.applyTargetContent(
                req, configuration.getTargetContentField());

        Map<String, Object> documentMap = new HashMap<>();
        documentMap.put("type", "add");
        documentMap.put(COULDSEARCH_ID_FIELD, extractId(req));
        Map<String, Object> fieldMap = new HashMap<>();
        for (Entry<String, List<String>> en : req.getMetadata().entrySet()) {
            var key = en.getKey();
            var values = en.getValue();
            if (!COULDSEARCH_ID_FIELD.equals(key)) {
                /*size = 1 : non-empty single-valued field
                  size > 1 : non-empty multi-valued field
                  size = 0 : empty field
                */
                var fixedKey = fixKey(key);
                if (values.size() == 1) {
                    fieldMap.put(fixedKey, values.get(0));
                } else if (values.size() > 1) {
                    fieldMap.put(fixedKey, values);
                } else {
                    fieldMap.put(fixedKey, "");
                }
            }
        }
        documentMap.put("fields", fieldMap);
        return new JSONObject(documentMap);
    }

    private JSONObject toJsonDocDelete(DeleteRequest req)
            throws CommitterException {
        Map<String, Object> documentMap = new HashMap<>();
        documentMap.put("type", "delete");
        documentMap.put(COULDSEARCH_ID_FIELD, extractId(req));
        return new JSONObject(documentMap);
    }

    private String extractId(CommitterRequest req) throws CommitterException {
        return fixBadIdValue(
                CommitterUtil.extractSourceIdValue(
                        req, configuration.getSourceIdField()));
    }

    private String fixBadIdValue(String value) throws CommitterException {
        if (StringUtils.isBlank(value)) {
            throw new CommitterException("Document id cannot be empty.");
        }

        if (configuration.isFixBadIds()) {
            var v = value.replaceAll(
                    "[^a-zA-Z0-9\\-\\_\\/\\#\\:\\.\\;\\&\\=\\?"
                            + "\\@\\$\\+\\!\\*'\\(\\)\\,\\%]",
                    "_");
            v = StringUtil.truncateWithHash(v, 128, "!");
            if (LOG.isDebugEnabled() && !value.equals(v)) {
                LOG.debug("Fixed document id from \"{}\" to \"{}\".", value, v);
            }
            return v;
        }
        return value;
    }

    private String fixKey(String key) {
        if (FIELD_PATTERN.matcher(key).matches()) {
            return key;
        }
        var fix = key;
        fix = fix.replaceFirst("^[^a-zA-Z0-9]", "");
        fix = StringUtils.truncate(fix, 63);
        fix = fix.replaceAll("[^a-zA-Z0-9_]", "_");
        fix = fix.toLowerCase(Locale.ENGLISH);
        LOG.warn(
                "\"{}\" field renamed to \"{}\" as it does not match "
                        + "CloudSearch required pattern: {}",
                key, fix, FIELD_PATTERN);
        return fix;
    }
}
