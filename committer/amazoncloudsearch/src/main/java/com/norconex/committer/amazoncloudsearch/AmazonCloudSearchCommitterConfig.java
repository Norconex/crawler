/* Copyright 2016-2025 Norconex Inc.
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

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.norconex.committer.core.batch.BaseBatchCommitterConfig;
import com.norconex.commons.lang.net.ProxySettings;

import lombok.Data;
import lombok.experimental.Accessors;

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
 * setting {@link #setFixBadIds(boolean)} to <code>true</code>.  It will
 * truncate references that are too long and append a hash code to it
 * to keep uniqueness.  It will also convert invalid
 * characters to underscore.  This approach is not 100%
 * collision-free (uniqueness), but it should safely cover the vast
 * majority of cases.
 * </p>

 */
@Data
@Accessors(chain = true)
public class AmazonCloudSearchCommitterConfig
        extends BaseBatchCommitterConfig {

    /** Default CloudSearch content field */
    public static final String DEFAULT_COULDSEARCH_CONTENT_FIELD = "content";

    /**
     * The AWS service endpoint.
     */
    private String serviceEndpoint;

    /**
     * The the AWS signing region.
     */
    private String signingRegion;

    /**
     * The CloudSearch access key. If {@code null}, the access key
     * will be obtained from the environment, as detailed in
     * {@link DefaultAWSCredentialsProviderChain}.
     */
    private String accessKey;

    /**
     * The CloudSearch secret key. If {@code null}, the secret key
     * will be obtained from the environment, as detailed in
     * {@link DefaultAWSCredentialsProviderChain}.
     */
    private String secretKey;

    /**
     * Whether to fix IDs that are too long for CloudSearch
     * ID limitation (128 characters max). If <code>true</code>,
     * long IDs will be truncated and a hash code representing the
     * truncated part will be appended.
     */
    private boolean fixBadIds;

    private final ProxySettings proxySettings = new ProxySettings();

    /**
     * The document field name containing the value to be stored
     * in CloudSearch "id" field. Default is not a field, but rather
     * the document reference.
     * A {@code null} value indicate to use the
     * document reference instead of a field (default).
     */
    private String sourceIdField;

    /**
     * The name of the CloudSearch field where content will be stored.
     * Default is "content".
     * Specifying a {@code null} value will disable storing the content.
     */
    private String targetContentField = DEFAULT_COULDSEARCH_CONTENT_FIELD;

    public ProxySettings getProxySettings() {
        return proxySettings;
    }

    public AmazonCloudSearchCommitterConfig setProxySettings(
            ProxySettings proxy) {
        proxySettings.copyFrom(proxy);
        return this;
    }
}
