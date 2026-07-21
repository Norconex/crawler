/* Copyright 2009-2026 Norconex Inc.
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
package com.norconex.committer.googlecloudsearch;

import java.util.Iterator;

import org.apache.commons.lang3.StringUtils;

import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.CommitterRequest;
import com.norconex.committer.core.batch.AbstractBatchCommitter;
import com.norconex.committer.googlecloudsearch.GoogleCloudSearchCommitterConfig.MetadataField;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * <p>
 * Commits documents to Google Cloud Search using the official Google API
 * client. The client root URL is configurable, which makes it possible to
 * run the committer against a local mock server during development and
 * tests.
 * </p>
 *
 * <h2>Raw vs text upload</h2>
 * <p>
 * Text uploads use the committer request content (the parsed/extracted
 * plain text). Raw uploads instead send the original, unparsed document
 * content, which must be captured ahead of time (since parsing normally
 * discards it) into the
 * {@value GoogleCloudSearchClient#FIELD_BINARY_CONTENT} metadata field
 * &#8212; e.g., using a {@code BinaryContentTransformer} pre-parse handler.
 * </p>
 *
 * <h2>ACL mapping</h2>
 * <p>
 * Metadata fields can optionally be mapped to Google Cloud Search ACL
 * principals (readers, denied readers, owners) via
 * {@link GoogleCloudSearchCommitterConfig#setAclMappings(java.util.List)},
 * and ACL inheritance from a parent item can be configured via
 * {@link GoogleCloudSearchCommitterConfig#setAclInheritance(
 * GoogleCloudSearchCommitterConfig.AclInheritanceMapping)}.
 * </p>
 */
@EqualsAndHashCode
@ToString
public class GoogleCloudSearchCommitter
        extends AbstractBatchCommitter<GoogleCloudSearchCommitterConfig> {

    @Getter
    private final GoogleCloudSearchCommitterConfig configuration =
            new GoogleCloudSearchCommitterConfig();

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private GoogleCloudSearchClient client;

    @Override
    protected void initBatchCommitter() throws CommitterException {
        if (StringUtils.isBlank(configuration.getSecretKeyPath())) {
            throw new CommitterException(
                    "Missing required configuration entry: secretKeyPath");
        }
        if (StringUtils.isBlank(configuration.getDataSourceId())) {
            throw new CommitterException(
                    "Missing required configuration entry: dataSourceId");
        }
        if (StringUtils.isBlank(configuration.getConnectorName())) {
            configuration.setConnectorName(configuration.getApplicationName());
        }
        for (var mapping : configuration.getMetadataMappings()) {
            if (mapping == null || StringUtils.isBlank(mapping.getToField())) {
                throw new CommitterException(
                        "Each metadata mapping must declare a non-blank toField.");
            }
            if (MetadataField.fromValue(mapping.getToField()) == null) {
                throw new CommitterException(
                        "Unsupported metadata mapping toField: "
                                + mapping.getToField());
            }
        }
        client = new GoogleCloudSearchClient(configuration);
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
