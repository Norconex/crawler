/* Copyright 2010-2024 Norconex Inc.
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
package com.norconex.committer.idol;

import java.util.Iterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.CommitterRequest;
import com.norconex.committer.core.batch.AbstractBatchCommitter;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * <p>
 * Commits documents to IDOL Server/DIH or Connector
 * Framework Server (CFS). Specifying either the index port or the cfs port
 * determines which of the two will be the documents target.
 * </p>

 */
@EqualsAndHashCode
@ToString
public class IdolCommitter
        extends AbstractBatchCommitter<IdolCommitterConfig> {

    private static final Logger LOG =
            LoggerFactory.getLogger(IdolCommitter.class);

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private IdolClient idolClient;

    @Getter
    private final IdolCommitterConfig configuration =
            new IdolCommitterConfig();

    @Override
    protected void initBatchCommitter() throws CommitterException {
        // IDOL Client
        idolClient = new IdolClient(configuration);
        LOG.info(
                "IDOL {}URL: {}",
                configuration.isCfs() ? "CFS " : "", configuration.getUrl());
    }

    @Override
    protected void commitBatch(Iterator<CommitterRequest> it)
            throws CommitterException {
        idolClient.post(it);
    }
}
