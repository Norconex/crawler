/* Copyright 2025-2026 Norconex Inc.
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
package com.norconex.crawler.core.session;

import java.util.Optional;

import com.norconex.crawler.core.cluster.CacheMap;
import com.norconex.crawler.core.util.SerialUtil;

import lombok.RequiredArgsConstructor;

/**
 * Manages persistent crawl-state and session-scoped attributes stored in the
 * distributed session cache. Can be injected directly in tests without the
 * full {@link CrawlerSession}.
 */
@RequiredArgsConstructor
public class CrawlerStateStore {

    static final String SESSION_STATE_KEY = "session.state";
    static final String START_REFS_QUEUED_KEY =
            "session.startRefsQueuingComplete";
    static final String START_REFS_ALL_QUEUED_KEY =
            "session.startRefsAllQueued";
    static final String START_REFS_QUEUEING_NODE_KEY =
            "session.startRefsQueueingNode";

    private final CacheMap<String> sessionCache;
    private final CrawlerAttributes sessionAttributes;
    /**
     * Ephemeral (non-MapStore-backed) attributes for per-run flags that must
     * survive partition migration after a node crash. Backed by the
     * {@code eph-crawlRun} cache so that in-memory backup promotion is used
     * directly without triggering a LAZY MapStore reload.
     */
    private final CrawlerAttributes runAttributes;

    // Local copy refreshed on each explicit getCrawlState() call
    private CrawlerSession.State cachedState;

    /**
     * Loads the current crawl state directly from the distributed cache.
     * @return the current state, or {@code null} if none has been saved yet
     */
    public CrawlerSession.State loadState() {
        return SerialUtil.fromJson(
                sessionCache.getOrDefault(SESSION_STATE_KEY, null),
                CrawlerSession.State.class);
    }

    /**
     * Updates the crawl state in the distributed cache with a new timestamp.
     * @param state the new crawl state
     */
    public void updateCrawlState(CrawlerState state) {
        saveState(new CrawlerSession.State()
                .setCrawlState(state)
                .setLastUpdated(System.currentTimeMillis()));
    }

    /**
     * Returns the current crawl state, reading fresh from the distributed
     * cache each call.
     * @return the current crawl state, or {@code null}
     */
    public CrawlerState getCrawlState() {
        cachedState = loadState();
        return cachedState != null ? cachedState.getCrawlState() : null;
    }

    /**
     * Returns whether start-reference queuing has completed.
     * @return {@code true} when all start references have been queued
     */
    public boolean isStartRefsQueueingComplete() {
        return runAttributes.getBoolean(START_REFS_QUEUED_KEY);
    }

    /**
     * Marks whether start-reference queuing has completed.
     * @param isComplete {@code true} once all start references are queued
     */
    public void setStartRefsQueueingComplete(boolean isComplete) {
        runAttributes.setBoolean(START_REFS_QUEUED_KEY, isComplete);
    }

    /**
     * Returns whether all start references of this crawl session were
     * queued, by this run or a previous one. Unlike
     * {@link #isStartRefsQueueingComplete()}, it persists across runs, so a
     * resumed crawl knows whether queueing was left unfinished.
     * @return {@code true} once every start reference was queued
     */
    public boolean isStartRefsAllQueued() {
        return sessionAttributes.getBoolean(START_REFS_ALL_QUEUED_KEY);
    }

    /**
     * Marks whether all start references of this crawl session were queued.
     * @param allQueued {@code true} once every start reference was queued
     */
    public void setStartRefsAllQueued(boolean allQueued) {
        sessionAttributes.setBoolean(START_REFS_ALL_QUEUED_KEY, allQueued);
    }

    /**
     * Returns the name of the node queueing start references in this run.
     * @return node name, or empty if queueing has not started
     */
    public Optional<String> getStartRefsQueueingNode() {
        return runAttributes.getString(START_REFS_QUEUEING_NODE_KEY);
    }

    /**
     * Records the name of the node queueing start references in this run.
     * @param nodeName node name
     */
    public void setStartRefsQueueingNode(String nodeName) {
        runAttributes.setString(START_REFS_QUEUEING_NODE_KEY, nodeName);
    }

    /**
     * Returns the session-scoped attribute store backed by the session cache.
     * @return session attributes
     */
    public CrawlerAttributes getSessionAttributes() {
        return sessionAttributes;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void saveState(CrawlerSession.State state) {
        sessionCache.put(SESSION_STATE_KEY, SerialUtil.toJsonString(state));
    }
}
