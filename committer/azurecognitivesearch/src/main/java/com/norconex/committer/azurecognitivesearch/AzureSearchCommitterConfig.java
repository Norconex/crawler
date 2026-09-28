/* Copyright 2021-2025 Norconex Inc.
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
package com.norconex.committer.azurecognitivesearch;

import java.io.Serializable;

import com.norconex.committer.core.batch.BaseBatchCommitterConfig;
import com.norconex.commons.lang.net.ProxySettings;

import lombok.Data;
import lombok.NonNull;
import lombok.experimental.Accessors;

/**
 * <p>
 * Azure Search Committer configuration.
 * </p>
 */
@Data
@Accessors(chain = true)
public class AzureSearchCommitterConfig
        extends BaseBatchCommitterConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Default Azure Search API version */
    public static final String DEFAULT_API_VERSION = "2020-06-30";
    /** Default Azure Search document key field */
    public static final String DEFAULT_AZURE_KEY_FIELD = "id";
    /** Default Azure Search content field */
    public static final String DEFAULT_AZURE_CONTENT_FIELD = "content";

    /**
     * Gets the Azure Search endpoint.
     * (https://[service name].search.windows.net).
     */
    private String endpoint;

    /**
     * The Azure API version. Default is {@link #DEFAULT_API_VERSION}.
     */
    private String apiVersion = DEFAULT_API_VERSION;

    /**
     * The Azure API admin key.
     */
    private String apiKey;

    /**
     * The target index name.
     */
    private String indexName;

    /**
     * Whether to disable document reference encoding. By default, references
     * are encoded using a URL-safe Base64 encoding.  When <code>true</code>,
     * document references will be sent as is if they pass validation.
     */
    private boolean disableDocKeyEncoding;

    /**
     * Whether to ignore validation errors.  By default, an exception is
     * thrown if a document contains a field that Azure Search will reject.
     * When <code>true</code> the validation errors are logged
     * instead and the faulty field or document is not committed.
     */
    private boolean ignoreValidationErrors;

    /**
     * Whether to ignore response errors.  By default, an exception is
     * thrown if the Azure Search response contains an error.
     * When <code>true</code> the errors are logged instead.
     */
    private boolean ignoreResponseErrors;

    /**
     * The fields which values should always be treated as array.
     * Expects a comma-separated-value list or regular expression when
     * the returned value of {@link #isArrayFieldsRegex()} is <code>true</code>.
     * @see #setArrayFieldsRegex(boolean)
     * @see #isArrayFieldsRegex()
     */
    private String arrayFields;

    /**
     * Whether the list of fields to be always treated as array
     * is represented as regular expression.
     * @see #setArrayFields(String)
     * @see #getArrayFields()
     */
    private boolean arrayFieldsRegex;

    /**
     * Whether to use integrated Windows Authentication (if applicable).
     */
    private boolean useWindowsAuth;

    private final ProxySettings proxySettings = new ProxySettings();

    /**
     * The document field name containing the value to be stored
     * in Azure Search document key field. Default is not a field
     * ({@code null}), but rather
     * the document reference.
     */
    private String sourceKeyField;

    /**
     * The name of Azure Search key field where to store a
     * document unique identifier (sourceKeyField).  Default is "id".
     */
    private String targetKeyField = DEFAULT_AZURE_KEY_FIELD;

    /**
     * The name of the Azure Search field where content will be stored.
     * Default is "content".
     * Specifying a {@code null} value will disable storing the content.
     */
    private String targetContentField = DEFAULT_AZURE_CONTENT_FIELD;

    /**
     * Gets the proxy settings.
     * @return proxy settings (never {@code null}).
     */
    public ProxySettings getProxySettings() {
        return proxySettings;
    }

    /**
     * Sets the proxy settings.
     * @param proxySettings proxy settings
     * @return this
     */
    public AzureSearchCommitterConfig setProxySettings(
            @NonNull ProxySettings proxySettings) {
        this.proxySettings.copyFrom(proxySettings);
        return this;
    }
}
