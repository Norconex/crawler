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
package com.norconex.committer.idol;

import java.util.Iterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.CommitterRequest;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.committer.core.acl.Acl;
import com.norconex.committer.core.acl.AclCodec;
import com.norconex.committer.core.acl.AclFormat;
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

    /**
     * IDOL has document security of its own, which reads an ACL from a field
     * (see {@link IdolAclSyntax}). By default the permissions the crawler
     * captured (the {@value Acl#FIELD} field) are written that way, to the
     * configured ACL field. Set the ACL format to JSON to send the field as
     * it is. Not available when sending to a Connector Framework Server.
     * @return {@code true} unless sending to a CFS
     */
    @Override
    protected boolean supportsNativeAcl() {
        return !configuration.isCfs();
    }

    @Override
    protected String nativeAclProblem(Acl acl) {
        return IdolAcls.problem(acl, configuration.getAclSyntax());
    }

    @Override
    protected void commitBatch(Iterator<CommitterRequest> it)
            throws CommitterException {
        idolClient.post(aclFormat() == AclFormat.NATIVE
                ? new NativeAclIterator(it)
                : it);
    }

    /** Replaces the ACL field of each document by the one IDOL reads. */
    private final class NativeAclIterator
            implements Iterator<CommitterRequest> {
        private final Iterator<CommitterRequest> it;

        NativeAclIterator(Iterator<CommitterRequest> it) {
            this.it = it;
        }

        @Override
        public boolean hasNext() {
            return it.hasNext();
        }

        @Override
        public CommitterRequest next() {
            var request = it.next();
            if (request instanceof UpsertRequest upsert) {
                var meta = upsert.getMetadata();
                AclCodec.read(meta).ifPresent(acl -> {
                    meta.remove(Acl.FIELD);
                    meta.set(configuration.getAclField(),
                            IdolAcls.write(acl, configuration.getAclSyntax()));
                    if (configuration.getSecurityType() != null) {
                        meta.set("SECURITYTYPE",
                                configuration.getSecurityType());
                    }
                });
            }
            return request;
        }
    }
}
