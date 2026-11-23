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
package com.norconex.crawler.core.doc.pipelines.committer.stages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.norconex.committer.core.CommitterContext;
import com.norconex.committer.core.CommitterException;
import com.norconex.committer.core.DeleteRequest;
import com.norconex.committer.core.UpsertRequest;
import com.norconex.committer.core.acl.Acl;
import com.norconex.committer.core.acl.AclCodec;
import com.norconex.committer.core.acl.AclEntry;
import com.norconex.committer.core.acl.AclKind;
import com.norconex.committer.core.acl.AclLevel;
import com.norconex.committer.core.acl.AclStatus;
import com.norconex.committer.core.impl.MemoryCommitter;
import com.norconex.committer.core.service.CommitterService;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.doc.CrawlerDocContext;
import com.norconex.crawler.core.doc.pipelines.ChecksumStageUtil;
import com.norconex.crawler.core.doc.pipelines.committer.CommitterPipelineContext;
import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.importer.doc.Doc;

@Timeout(30)
class CommitModuleStageTest {

    private static Acl acl(AclStatus status) {
        return Acl.complete(List.of(new AclLevel("file", List.of(
                AclEntry.allow(AclKind.GROUP, "ad:CORP", "Sales")))),
                List.of(), null).withStatus(status);
    }

    /** Runs the stage on a document, returning the entry kept for it. */
    private static CrawlerEntry commit(MemoryCommitter committer, Acl acl) {
        var service = CommitterService.<Doc>builder()
                .committers(List.of(committer))
                .upsertRequestBuilder(d -> new UpsertRequest(
                        d.getReference(), d.getMetadata(), d.getInputStream()))
                .deleteRequestBuilder(d -> new DeleteRequest(
                        d.getReference(), d.getMetadata()))
                .build();
        var crawlCtx = mock(CrawlerContext.class);
        when(crawlCtx.getCommitterService()).thenReturn(service);
        var session = mock(CrawlerSession.class);
        when(session.getCrawlContext()).thenReturn(crawlCtx);

        var doc = new Doc("file:///sales/q3.xlsx");
        AclCodec.write(doc.getMetadata(), acl);
        var entry = new CrawlerEntry(doc.getReference());
        entry.setProcessingOutcome(ProcessingOutcome.NEW);
        entry.setMetaChecksum("meta-1");
        entry.setContentChecksum("content-1");
        var docCtx = CrawlerDocContext.builder()
                .doc(doc)
                .currentCrawlEntry(entry)
                .build();

        new CommitModuleStage().test(
                new CommitterPipelineContext(session, docCtx));
        return entry;
    }

    private static MemoryCommitter memoryCommitter()
            throws CommitterException {
        var committer = new MemoryCommitter();
        committer.init(CommitterContext.builder().build());
        return committer;
    }

    @Test
    void aWithheldDocumentIsTriedAgainOnTheNextRunEvenIfUnchanged()
            throws CommitterException {
        var committer = memoryCommitter();

        var kept = commit(committer, acl(AclStatus.PARTIAL));

        assertThat(committer.getUpsertRequests()).isEmpty();
        assertThat(kept.getMetaChecksum()).isNull();
        assertThat(kept.getContentChecksum()).isNull();
        // Next run, with that entry as the previous one and the very same
        // metadata: the document is not taken as unmodified.
        var next = CrawlerDocContext.builder()
                .doc(new Doc(kept.getReference()))
                .currentCrawlEntry(new CrawlerEntry(kept.getReference()))
                .previousCrawlEntry(kept)
                .build();
        assertThat(ChecksumStageUtil.resolveMetaChecksum("meta-1", next))
                .isTrue();
        assertThat(next.getCurrentCrawlEntry().getProcessingOutcome())
                .isEqualTo(ProcessingOutcome.MODIFIED);
    }

    @Test
    void aSentDocumentKeepsItsChecksums() throws CommitterException {
        var committer = memoryCommitter();

        var kept = commit(committer, acl(AclStatus.COMPLETE));

        assertThat(committer.getUpsertRequests()).hasSize(1);
        assertThat(kept.getMetaChecksum()).isEqualTo("meta-1");
        assertThat(kept.getContentChecksum()).isEqualTo("content-1");
    }
}
