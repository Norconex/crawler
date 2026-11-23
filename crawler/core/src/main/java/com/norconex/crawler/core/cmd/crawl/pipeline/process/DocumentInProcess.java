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

import java.util.Objects;

import com.norconex.commons.lang.bean.BeanUtil;
import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.ledger.CrawlerEntryLedger;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * A document a crawl thread is processing, from the moment it is taken from
 * the queue until it and the documents found in it are done with. Decides
 * whether what is made of it may still be sent to committers or recorded in
 * the ledger, so that no document is sent twice:
 * <ul>
 *   <li>Once the crawler gave up on it (it took too long), nothing more of
 *       it is sent or recorded: its outcome was recorded when it was given
 *       up on.</li>
 *   <li>In a cluster, the first send marks the ledger entry as being sent,
 *       and recording its outcome replaces the entry, both only if this node
 *       still holds the entry. If it was given to another node (this one was
 *       taken for gone, then came back), nothing more of it is sent or
 *       recorded here: the other node does both.</li>
 * </ul>
 * The processing thread is bound to it, for {@link #maySend()} to find it
 * whatever sends to committers.
 */
@Slf4j
public final class DocumentInProcess {

    private static final ThreadLocal<DocumentInProcess> CURRENT =
            new ThreadLocal<>();

    /** What to do on giving up on a document. */
    enum TimeoutAction {
        /** Already given up on. */
        NONE,
        /** Record the document's outcome as an error. */
        RECORD_ERROR,
        /** Its outcome is (being) recorded: only stop sending more of it. */
        STOP_SENDING
    }

    private final CrawlerEntryLedger ledger;
    /** The entry as taken from the queue, with its claim. */
    @Getter
    private final CrawlerEntry claimedEntry;
    @Getter
    private final Thread thread;
    @Getter
    private final long startedAt;
    /** Whether another node may take this document over (clustered). */
    @Getter
    private final boolean clustered;

    private boolean timedOut;
    private boolean lost;
    // Holding the entry was confirmed: sending needs no more checking.
    private boolean confirmed;
    private boolean recording;
    private boolean recorded;
    private boolean refusalLogged;

    private DocumentInProcess(CrawlerEntryLedger ledger,
            CrawlerEntry claimedEntry, boolean clustered) {
        this.ledger = ledger;
        this.claimedEntry = BeanUtil.clone(claimedEntry);
        this.clustered = clustered;
        confirmed = !clustered;
        thread = Thread.currentThread();
        startedAt = System.currentTimeMillis();
    }

    /**
     * Starts tracking a document processed by the current thread.
     * @param ledger the crawl entry ledger
     * @param claimedEntry the entry taken from the queue
     * @param clustered whether other nodes may take it over
     * @return the document in process, bound to the current thread
     */
    static DocumentInProcess begin(CrawlerEntryLedger ledger,
            CrawlerEntry claimedEntry, boolean clustered) {
        var doc = new DocumentInProcess(ledger, claimedEntry, clustered);
        CURRENT.set(doc);
        return doc;
    }

    /**
     * The document the current thread is processing.
     * @return the document in process, or {@code null}
     */
    static DocumentInProcess current() {
        return CURRENT.get();
    }

    /** Stops tracking the document on the current thread. */
    void end() {
        if (CURRENT.get() == this) {
            CURRENT.remove();
        }
    }

    String getReference() {
        return claimedEntry.getReference();
    }

    boolean isFor(String reference) {
        return Objects.equals(getReference(), reference);
    }

    /**
     * Whether the document processed by the current thread may be sent to
     * committers (it, or a document found in it). Always {@code true} when
     * the current thread is not processing a document.
     * @return {@code true} if it may be sent
     */
    public static boolean maySend() {
        var doc = CURRENT.get();
        return doc == null || doc.allowsSending();
    }

    synchronized boolean allowsSending() {
        if (timedOut || lost) {
            logRefusal(timedOut
                    ? "the crawler gave up on it, its processing having "
                            + "taken too long"
                    : "it was given to another node, this one having been "
                            + "taken for gone");
            return false;
        }
        if (!confirmed) {
            if (!ledger.markSending(claimedEntry)) {
                lost = true;
                logRefusal("it was given to another node, this one having "
                        + "been taken for gone");
                return false;
            }
            confirmed = true;
        }
        return true;
    }

    /**
     * Whether what is made of the document (it, or documents found in it)
     * may be recorded in the ledger.
     * @return {@code true} if it may
     */
    synchronized boolean mayRecord() {
        return !timedOut && !lost;
    }

    /**
     * Called before recording the outcome of the document taken from the
     * queue.
     * @return {@code false} if it must not be recorded
     */
    synchronized boolean startRecordingOutcome() {
        if (timedOut || lost) {
            return false;
        }
        recording = true;
        return true;
    }

    /**
     * Called once the outcome of the document taken from the queue was
     * recorded, or could not be because the entry is no longer held.
     * @param held whether it was recorded
     */
    synchronized void outcomeRecorded(boolean held) {
        recording = false;
        if (held) {
            recorded = true;
            confirmed = true;
        } else {
            lost = true;
        }
    }

    /**
     * Gives up on the document: nothing more of it is sent or recorded.
     * @return what the caller must do about its outcome
     */
    synchronized TimeoutAction timeOut() {
        if (timedOut) {
            return TimeoutAction.NONE;
        }
        timedOut = true;
        return recording || recorded || lost
                ? TimeoutAction.STOP_SENDING
                : TimeoutAction.RECORD_ERROR;
    }

    synchronized boolean isTimedOut() {
        return timedOut;
    }

    private void logRefusal(String reason) {
        if (!refusalLogged) {
            refusalLogged = true;
            LOG.warn("Not sending {} (or documents found in it) to "
                    + "committers: {}.", getReference(), reason);
        }
    }
}
