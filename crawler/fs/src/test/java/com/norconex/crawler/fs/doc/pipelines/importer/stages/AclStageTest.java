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
package com.norconex.crawler.fs.doc.pipelines.importer.stages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.norconex.committer.core.acl.Acl;
import com.norconex.committer.core.acl.AclCodec;
import com.norconex.committer.core.acl.AclEntry;
import com.norconex.committer.core.acl.AclKind;
import com.norconex.committer.core.acl.AclLevel;
import com.norconex.committer.core.acl.AclScope;
import com.norconex.committer.core.acl.AclStatus;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.doc.CrawlerDocContext;
import com.norconex.crawler.core.doc.pipelines.importer.ImporterPipelineContext;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.fetch.FetchDirective;
import com.norconex.crawler.core.fetch.FetchDirectiveSupport;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.fs.FsCrawlerConfig;
import com.norconex.crawler.fs.ledger.FsCrawlerEntry;
import com.norconex.importer.doc.Doc;

class AclStageTest {

    private static final Acl COMPLETE = Acl.complete(List.of(new AclLevel(
            "file", List.of(AclEntry.allow(AclKind.USER, null, "pat")))),
            List.of(), null);
    private static final Acl PARTIAL = COMPLETE
            .withStatus(AclStatus.PARTIAL).withUnread(List.of("owner"));
    private static final Acl NONE = Acl.none(AclScope.BASIC, "no access");

    private CrawlerSession session;
    private FsCrawlerEntry entry;

    private ImporterPipelineContext ctx(
            boolean reject, boolean folder, Acl acl) {
        var config = new FsCrawlerConfig();
        config.setRejectIncompleteAcl(reject);
        config.setDocumentFetchSupport(FetchDirectiveSupport.REQUIRED);
        var crawlContext = mock(CrawlerContext.class);
        when(crawlContext.getCrawlConfig()).thenReturn(config);
        session = mock(CrawlerSession.class);
        when(session.getCrawlContext()).thenReturn(crawlContext);
        entry = new FsCrawlerEntry("file:///sales/q3.xlsx");
        entry.setFolder(folder);
        entry.setFile(!folder);
        var doc = new Doc(entry.getReference());
        if (acl != null) {
            AclCodec.write(doc.getMetadata(), acl);
        }
        return new ImporterPipelineContext(session,
                CrawlerDocContext.builder().doc(doc)
                        .currentCrawlEntry(entry).build());
    }

    private static boolean passes(ImporterPipelineContext ctx) {
        return new AclStage(FetchDirective.DOCUMENT).test(ctx);
    }

    @Test
    void aFileWithItsPermissionsReadInFullGoesOn() {
        assertThat(passes(ctx(true, false, COMPLETE))).isTrue();
        verify(session, never()).fire(any());
    }

    @Test
    void aFileWhosePermissionsWereNotAskedForGoesOn() {
        assertThat(passes(ctx(true, false, null))).isTrue();
    }

    @Test
    void anIncompleteFileGoesOnUnlessTheCrawlerIsToldToRejectIt() {
        assertThat(passes(ctx(false, false, PARTIAL))).isTrue();
        assertThat(passes(ctx(false, false, NONE))).isTrue();
        verify(session, never()).fire(any());
    }

    @Test
    void aFileWithPartialOrNoPermissionsIsRejectedWithWhatWasNotRead() {
        for (var acl : List.of(PARTIAL, NONE)) {
            assertThat(passes(ctx(true, false, acl))).isFalse();
            assertThat(entry.getProcessingOutcome())
                    .isEqualTo(ProcessingOutcome.REJECTED);
            var event = ArgumentCaptor.forClass(CrawlerEvent.class);
            verify(session).fire(event.capture());
            assertThat(event.getValue().getName())
                    .isEqualTo(CrawlerEvent.REJECTED_ACL);
            assertThat(event.getValue().getMessage())
                    .contains(acl == PARTIAL ? "in part" : "could not be read");
        }
    }

    @Test
    void aFolderIsNeverRejectedForItsPermissions() {
        assertThat(passes(ctx(true, true, PARTIAL))).isTrue();
        verify(session, never()).fire(any());
    }
}
