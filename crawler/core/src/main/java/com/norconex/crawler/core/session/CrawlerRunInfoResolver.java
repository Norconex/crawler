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

import java.util.Objects;
import java.util.Optional;

import com.norconex.crawler.core.metrics.CrawlerMetricsImpl;
import com.norconex.crawler.core.util.SerialUtil;

import de.huxhorn.sulky.ulid.ULID;
import lombok.extern.slf4j.Slf4j;

/**
 * Establishes the current crawl run information specific to this run.
 * Given its immutable nature, it should be resolved when a session starts
 * (node joining a cluster).
 */
@Slf4j
public final class CrawlerRunInfoResolver {

    public static final String CRAWL_RUN_ID_KEY = "crawlRunId";
    public static final String CRAWL_RUN_INFO_KEY = "crawlRunInfo";

    private CrawlerRunInfoResolver() {
    }

    /**
     * Resolve the current crawl run info
     * @param session crawl session for which we get the run info
     * @return crawl run info
     */
    public static CrawlerRunInfo resolve(CrawlerSession session) {
        var runId = resolveCrawlRunId(session);
        var runCache = session.getCluster().getCacheManager()
                .getCrawlRunCache();
        var sessCache = session.getCluster().getCacheManager()
                .getCrawlSessionCache();

        // Fast-path: if another node already published the run info for
        // this run in the ephemeral run cache, adopt it.
        var existingJson = runCache.get(CRAWL_RUN_INFO_KEY).orElse(null);
        if (existingJson != null) {
            return SerialUtil.fromJson(existingJson, CrawlerRunInfo.class);
        }

        // Try to derive next CrawlerRunInfo based on prior persisted state
        var prior = load(session).orElse(null);
        LOG.debug("Prior run info loaded from cache: {}", prior);
        final CrawlerRunInfo info;
        if (prior == null) {
            LOG.info("No active or previous crawl session detected, "
                    + "starting a new one.");
            info = newInfoForNewSession(session, runId);
        } else if (Objects.equals(prior.getCrawlRunId(), runId)) {
            LOG.info("Found an active crawl session. Joining it.");
            info = prior;
        } else {
            var timedState = session.loadState();
            LOG.debug("Loaded state for resume detection: {}", timedState);
            if (timedState == null || timedState.getCrawlState() == null) {
                LOG.warn("Prior run found but state is missing/invalid. "
                        + "Starting fresh.");
                info = newInfoForNewSession(session, runId);
            } else {
                info = buildForNextRun(session, runId, prior, timedState);
            }
        }

        // Elect exactly one creator for this run's info using putIfAbsent.
        var json = SerialUtil.toJsonString(info);
        var prev = runCache.putIfAbsent(CRAWL_RUN_INFO_KEY, json);
        if (prev != null) {
            // Another node won the election; adopt the winning info.
            return SerialUtil.fromJson(prev, CrawlerRunInfo.class);
        }

        // We are the elected creator for this run. Perform one-time init.
        if (info.getCrawlResumeState() == CrawlerResumeState.NEW) {
            // A new session's numbers are its own. Event counts are meant to
            // span the runs of one session -- a crawl stopped and resumed did
            // the work once -- but the store outlives sessions, so without
            // this every recrawl of a source reported its own counts plus
            // those of every crawl before it.
            //
            // Note this covers two ways of arriving at a new session: nothing
            // persisted at all, and a previously COMPLETED session followed
            // by another crawl. The clear below only covers the first.
            CrawlerMetricsImpl.clearPersistedEventCounts(
                    session.getCluster().getCacheManager());
        }

        if (prior == null) {
            LOG.info(
                    "Clearing any previous session-specific cache information");
            sessCache.clear();
        } else if (info.getCrawlResumeState() == CrawlerResumeState.RESUMED) {
            // When resuming, clear pipeline terminal state so coordinator
            // can restart from where it left off instead of immediately exiting
            LOG.info(
                    "Resuming crawl - clearing terminal pipeline state to allow restart");
            clearPipelineTerminalState(session, info);
        }
        // Persist to session cache for cross-run reference and durability.
        save(session, info);
        return info;
    }

    private static String resolveCrawlRunId(CrawlerSession session) {
        // Always generate a new crawl run id per process start. The
        // previous run id is persisted as part of CrawlerRunInfo in the
        // session cache and is used for resume detection. Reusing the
        // same run id across process lifecycles can prevent us from
        // correctly detecting prior STOPPED/FAILED runs.
        var newId = "cr-" + new ULID().nextULID();
        var cache = session.getCluster().getCacheManager().getCrawlRunCache();
        cache.put(CRAWL_RUN_ID_KEY, newId);
        return newId;
    }

    private static CrawlerRunInfo newInfoForNewSession(
            CrawlerSession session, String runId) {
        LOG.info("No previous crawl session detected. Going fresh.");
        return CrawlerRunInfo.builder()
                .crawlRunId(runId)
                .crawlSessionId("cs-" + new ULID().nextULID())
                .crawlerId(session.getCrawlerId())
                .crawlResumeState(CrawlerResumeState.NEW)
                .crawlMode(CrawlerMode.FULL)
                .build();
    }

    private static CrawlerRunInfo buildForNextRun(
            CrawlerSession session,
            String runId,
            CrawlerRunInfo previous,
            CrawlerSession.State timedState) {
        var b = CrawlerRunInfo.builder()
                .crawlerId(session.getCrawlerId())
                .crawlRunId(runId);

        return switch (timedState.getCrawlState()) {
            case RUNNING -> {
                LOG.warn("A previously aborted session was detected for "
                        + "crawler {}. Trying to resume it.",
                        session.getCrawlerId());
                yield b.crawlMode(previous.getCrawlMode())
                        .crawlSessionId(previous.getCrawlSessionId())
                        .crawlResumeState(CrawlerResumeState.RESUMED)
                        .build();
            }
            case STOPPED -> {
                LOG.info("A previously stopped crawl session was detected for "
                        + "crawler {}. Resuming it.", session.getCrawlerId());
                yield b.crawlMode(previous.getCrawlMode())
                        .crawlSessionId(previous.getCrawlSessionId())
                        .crawlResumeState(CrawlerResumeState.RESUMED)
                        .build();
            }
            case COMPLETED -> {
                LOG.info("""
                    A previously completed crawl session was detected \
                    for crawler {}. Starting a new incremental crawl \
                    session.""", session.getCrawlerId());
                yield b.crawlMode(CrawlerMode.INCREMENTAL)
                        .crawlSessionId("cs-" + new ULID().nextULID())
                        .crawlResumeState(CrawlerResumeState.NEW)
                        .build();
            }
            case FAILED -> {
                LOG.warn("A crawl session for crawler {} was detected but is "
                        + "marked as failed. Trying to resume it.",
                        session.getCrawlerId());
                yield b.crawlMode(previous.getCrawlMode())
                        .crawlSessionId(previous.getCrawlSessionId())
                        .crawlResumeState(CrawlerResumeState.RESUMED)
                        .build();
            }
        };
    }

    private static Optional<CrawlerRunInfo> load(CrawlerSession session) {
        var cache = session.getCluster()
                .getCacheManager()
                .getCrawlSessionCache();
        return cache.get(CRAWL_RUN_INFO_KEY)
                .map(json -> SerialUtil.fromJson(json, CrawlerRunInfo.class));
    }

    private static void save(CrawlerSession session, CrawlerRunInfo info) {
        session.getCluster()
                .getCacheManager()
                .getCrawlSessionCache()
                .put(CRAWL_RUN_INFO_KEY, SerialUtil.toJsonString(info));
    }

    /**
     * Clears pipeline terminal state when resuming a crawl. This allows
     * the coordinator to restart processing from where it left off instead
     * of treating the previous STOPPED/FAILED state as terminal and exiting
     * immediately.
     */
    private static void clearPipelineTerminalState(
            CrawlerSession session, CrawlerRunInfo info) {
        var cm = session.getCluster().getCacheManager();
        var sessionIdPrefix = info.getCrawlSessionId() + ":";

        // The pipeline caches are persistent and keyed with crawlSessionId.
        // If a previous run stopped/failed, the coordinator may see terminal
        // pipeline status and refuse to restart unless we clear those records.
        var stepCache = cm.getPipelineStepCache();
        var stepKeys = stepCache.keys().stream()
                .filter(key -> key.startsWith(sessionIdPrefix))
                .toList();
        stepKeys.forEach(stepCache::remove);

        // Worker statuses are per run (keys include crawlRunId) but we clear
        // them as well to avoid stale progress/status accumulation.
        var workerCache = cm.getPipelineWorkerStatusCache();
        var workerKeys = workerCache.keys().stream()
                .filter(key -> key.startsWith(sessionIdPrefix))
                .toList();
        workerKeys.forEach(workerCache::remove);

        LOG.debug(
                "Cleared {} pipeline step records and {} worker status records for resume",
                stepKeys.size(),
                workerKeys.size());
    }
}
