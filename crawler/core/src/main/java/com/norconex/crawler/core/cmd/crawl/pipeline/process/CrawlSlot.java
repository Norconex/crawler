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

import java.util.concurrent.CompletableFuture;

import lombok.Getter;

/**
 * One of the crawl threads a node processes the queue with. The thread
 * running it can be replaced: when one is stuck on a document past its
 * timeout, a new thread takes its place, and the stuck one stops when (if
 * ever) it lets go of the document.
 */
final class CrawlSlot {

    @Getter
    private final int index;
    /** Completed once the slot is done processing the queue. */
    @Getter
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    private Thread owner;
    private DocumentInProcess current;

    CrawlSlot(int index) {
        this.index = index;
    }

    synchronized void setOwner(Thread thread) {
        owner = thread;
    }

    synchronized boolean isOwnedByCurrentThread() {
        return owner == Thread.currentThread();
    }

    synchronized DocumentInProcess getCurrent() {
        return current;
    }

    synchronized void setCurrent(DocumentInProcess doc) {
        current = doc;
    }

    /**
     * Stops tracking a document, if it is still this slot's current one.
     * @param doc the document done with
     */
    synchronized void clearCurrent(DocumentInProcess doc) {
        if (current == doc) {
            current = null;
        }
    }

    /**
     * Interrupts the thread processing a document, if it still is.
     * @param doc the document given up on
     */
    synchronized void interruptIfCurrent(DocumentInProcess doc) {
        if (current == doc) {
            doc.getThread().interrupt();
        }
    }

    /**
     * Lets go of a thread still busy with a document given up on, for a new
     * thread to take its place.
     * @param doc the document given up on
     * @return {@code true} if the thread was let go of
     */
    synchronized boolean abandonIfCurrent(DocumentInProcess doc) {
        if (current != doc || owner != doc.getThread()) {
            return false;
        }
        current = null;
        owner = null;
        return true;
    }
}
