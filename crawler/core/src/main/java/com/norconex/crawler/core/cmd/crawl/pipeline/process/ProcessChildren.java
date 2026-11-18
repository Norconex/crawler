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
package com.norconex.crawler.core.cmd.crawl.pipeline.process;

import com.norconex.commons.lang.bean.BeanUtil;
import com.norconex.crawler.core.doc.CrawlerDocContext;
import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.ledger.ProcessingOutcome;
import com.norconex.crawler.core.ledger.ProcessingStatus;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.importer.doc.Doc;

import lombok.extern.slf4j.Slf4j;

/**
 * What becomes of the documents split off from, or embedded in, another one
 * (the chunks of a text, the entries of an archive, the attachments of an
 * email) when their parent is dealt with in a crawl.
 * <p>
 * Such a document cannot be fetched by itself: it only exists as long as its
 * parent produces it. So a child is never an orphan to fetch again. Its fate
 * is decided by what happens to its parent, and only then, which also means a
 * child is never dealt with before its parent, whatever order the ledger
 * returns entries in:
 * </p>
 * <ul>
 *   <li>The parent was not processed again because it is unchanged, or
 *       because it had a problem that is given another chance: its children
 *       are kept as they were ({@link #keep}).</li>
 *   <li>The parent was processed again: its children were made again and
 *       replace the previous ones. The previous ones it did not make again are
 *       stale and are deleted ({@link #deleteStale}).</li>
 *   <li>The parent is deleted, or is now rejected: its children are deleted
 *       ({@link #deleteAll}).</li>
 * </ul>
 * <p>
 * The parent only holds how many children it has
 * ({@link CrawlerEntry#getChildCount()}), and a child its parent's
 * reference, so a document without children costs nothing here.
 * </p>
 */
@Slf4j
public final class ProcessChildren {

    private ProcessChildren() {
    }

    /**
     * Keeps the children of the previous crawl of a parent that was not
     * processed again, so they are no longer waiting to be treated as
     * orphans, and the crawl after this one still knows them.
     * @param session the crawl session
     * @param current the parent as of this crawl
     * @param previous the parent as of the previous crawl
     */
    public static void keep(
            CrawlerSession session, CrawlerEntry current,
            CrawlerEntry previous) {
        if (previous == null || previous.getChildCount() <= 0) {
            return;
        }
        // The count is only known by what the previous crawl wrote.
        current.setChildCount(previous.getChildCount());
        var ledger = session.getCrawlContext().getCrawlEntryLedger();
        for (var child : ledger.getBaselineChildren(previous.getReference())) {
            var kept = BeanUtil.clone(child);
            kept.setProcessingOutcome(ProcessingOutcome.UNMODIFIED);
            kept.setProcessingStatus(ProcessingStatus.PROCESSED);
            kept.setOrphan(false);
            ledger.updateEntry(kept);
            keep(session, kept, child);
        }
    }

    /**
     * Deletes the children of the previous crawl that a parent processed
     * again did not make again: what is left of them in the baseline.
     * @param session the crawl session
     * @param previous the parent as of the previous crawl
     */
    public static void deleteStale(
            CrawlerSession session, CrawlerEntry previous) {
        deleteAll(session, previous);
    }

    /**
     * Deletes every child of the previous crawl of a parent that is deleted
     * or no longer wanted, and theirs in turn.
     * @param session the crawl session
     * @param previous the parent as of the previous crawl
     */
    public static void deleteAll(
            CrawlerSession session, CrawlerEntry previous) {
        if (previous == null || previous.getChildCount() <= 0) {
            return;
        }
        var ledger = session.getCrawlContext().getCrawlEntryLedger();
        for (var child : ledger.getBaselineChildren(previous.getReference())) {
            delete(session, child);
        }
    }

    /**
     * Deletes a child found in the baseline whose parent is nowhere to be
     * found: nothing will ever make it again.
     * @param session the crawl session
     * @param child the child as of the previous crawl
     */
    public static void deleteDangling(
            CrawlerSession session, CrawlerEntry child) {
        delete(session, child);
    }

    /**
     * Whether a baseline entry is the child of a parent that is still known,
     * in this crawl or the previous one, and so is dealt with through it.
     * @param session the crawl session
     * @param entry an entry of the baseline
     * @return {@code true} if the parent will decide what becomes of it
     */
    public static boolean hasKnownParent(
            CrawlerSession session, CrawlerEntry entry) {
        if (entry.getParentReference() == null) {
            return false;
        }
        var ledger = session.getCrawlContext().getCrawlEntryLedger();
        return ledger.exists(entry.getParentReference())
                || ledger.getBaselineEntry(entry.getParentReference())
                        .isPresent();
    }

    private static void delete(CrawlerSession session, CrawlerEntry child) {
        var entry = BeanUtil.clone(child);
        if (!ProcessFinalize.mayExistInTarget(child)) {
            // Never sent, or already deleted: nothing to ask the committers
            // for, but it is over for the ledger, and for its own children.
            entry.setProcessingOutcome(ProcessingOutcome.DELETED);
            entry.setProcessingStatus(ProcessingStatus.PROCESSED);
            session.getCrawlContext().getCrawlEntryLedger().updateEntry(entry);
            deleteAll(session, child);
            return;
        }
        var docContext = CrawlerDocContext.builder()
                .currentCrawlEntry(entry)
                .previousCrawlEntry(child)
                .doc(new Doc(child.getReference()))
                .build();
        ProcessDelete.execute(new ProcessContext()
                .crawlSession(session)
                .docContext(docContext));
    }
}
