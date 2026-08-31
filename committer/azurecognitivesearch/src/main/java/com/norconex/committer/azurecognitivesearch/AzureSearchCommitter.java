/* Copyright 2017-2024 Norconex Inc.
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

import java.util.Iterator;

import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.CommitterRequest;
import com.norconex.committer.core.batch.AbstractBatchCommitter;
import com.norconex.committer.core.batch.queue.impl.FsQueue;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * <p>
 * Commits documents to Microsoft Azure Search.
 * </p>
 *
 * <h2>Document reference encoding</h2>
 * <p>
 * By default the document reference (Azure Search Document Key) is
 * encoded using URL-safe Base64 encoding. This is Azure Search recommended
 * approach when a document unique id can contain special characters
 * (e.g. a URL).  If you know your document references to be safe
 * (e.g. a sequence number), you can
 * set {@link AzureSearchCommitterConfig#setDisableDocKeyEncoding(boolean)}
 * to <code>true</code>.
 * To otherwise store a reference value un-encoded, you can additionally
 * store it in a field other than your reference ("id") field.
 * </p>
 *
 * <h2>Single vs multiple values</h2>
 * <p>
 * Fields with single value will be sent as such, while multi-value fields
 * are sent as array. If you have a field defined as an array in Azure Search,
 * sending a single value may cause an error.
 * </p>
 * <p>
 * It is possible for values to always
 * be sent as arrays for specific fields. This is done using
 * {@link AzureSearchCommitterConfig#setArrayFields(String)}.
 * It expects comma-separated-value list
 * or a regular expression, depending of the value you set for
 * {@link AzureSearchCommitterConfig#setArrayFieldsRegex(boolean)}.
 * </p>
 *
 * <h2>Field names and errors</h2>
 * <p>
 * Azure Search will produce an error if any of the documents in a submitted
 * batch contains one or more fields with invalid characters.  To prevent
 * sending those in vain, the committer will validate your fields
 * and throw an exception upon encountering an invalid one.
 * To prevent exceptions from being thrown, you can set
 * {@link AzureSearchCommitterConfig#setIgnoreValidationErrors(boolean)}
 * to <code>true</code> to log those errors instead.
 * </p>
 * <p>
 * An exception will also be thrown for errors returned by Azure Search
 * (e.g. a field is not defined in your
 * Azure Search schema). To also log those errors instead of throwing an
 * exception, you can set
 * {@link AzureSearchCommitterConfig#setIgnoreResponseErrors(boolean)}
 * to <code>true</code>.
 * </p>
 * <h2>Field naming rules</h2>
 * <p>
 * Those are the field naming rules mandated for Azure Search (in force
 * for Azure Search version 2016-09-01):
 * Search version
 * </p>
 * <ul>
 *   <li><b>Document reference (ID):</b> Letters, numbers, dashes ("-"),
 *       underscores ("_"), and equal signs ("="). First character cannot be
 *       an underscore.</li>
 *   <li><b>Document field name:</b> Letters, numbers, underscores ("_"). First
 *       character must be a letter. Cannot start with "azureSearch".
 *       Maximum length is 128 characters.</li>
 * </ul>

 */
@EqualsAndHashCode
@ToString
public class AzureSearchCommitter
        extends AbstractBatchCommitter<AzureSearchCommitterConfig> {

    @Getter
    private final AzureSearchCommitterConfig configuration =
            new AzureSearchCommitterConfig();

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private AzureSearchClient client;

    @Override
    protected void initBatchCommitter() throws CommitterException {
        client = new AzureSearchClient(configuration);
        if (configuration.getQueue() instanceof FsQueue queue &&
                queue.getConfiguration().getBatchSize() > 1000) {
            throw new CommitterException(
                    "Commit batch size cannot be greater than 1000.");
        }
    }

    @Override
    protected void commitBatch(Iterator<CommitterRequest> it)
            throws CommitterException {
        client.post(it);
    }

    @Override
    protected void closeBatchCommitter() throws CommitterException {
        if (client != null) {
            client.close();
        }
        client = null;
    }
}
