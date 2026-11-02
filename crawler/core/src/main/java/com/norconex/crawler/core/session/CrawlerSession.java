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

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.function.Supplier;

import com.norconex.commons.lang.event.Event;
import com.norconex.crawler.core.CrawlerException;
import com.norconex.crawler.core.cluster.Cluster;
import com.norconex.crawler.core.cluster.admin.ClusterAdminServer;
import com.norconex.crawler.core.context.CrawlerContext;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.util.ExceptionSwallower;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/**
 * A crawl session, representing a crawl from begin to finish, which may
 * include zero to multiple "resumes" from stops, or failures. There is
 * one crawl session per cluster, each node having their own instance
 * of this class, exposing the same information.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Slf4j
@ToString(onlyExplicitlyIncluded = true)
public class CrawlerSession implements Closeable {

    @Getter
    private final Cluster cluster;
    private ClusterAdminServer adminServer;
    @Getter
    private final CrawlerContext crawlContext;

    /** Coordinates task deduplication across session and run scopes. */
    @Getter
    private CrawlerRunCoordinator runCoordinator;

    /** Manages persistent crawl state and session-scoped attributes. */
    @Getter
    private CrawlerStateStore stateStore;

    /** Fires events through the underlying event manager. */
    @Getter
    private CrawlerEventBus eventBus;

    private CrawlerAttributes crawlerAttributes;

    private CrawlerRunInfo crawlRunInfo;
    private boolean closed;
    private Runnable postCloseCleanup;

    /**
     * The crawler unique identifier, for a specific crawler setup
     * and does not change across sessions. Same as configured in the crawler
     * configuration.
     * @return crawler id
     */
    @ToString.Include(name = "crawlerId")
    public String getCrawlerId() {
        return crawlContext.getId();
    }

    public void oncePerSession(String taskId, Runnable runnable) {
        runCoordinator.oncePerSession(taskId, runnable);
    }

    public <T> T oncePerSessionAndGet(String taskId, Supplier<T> supplier) {
        return runCoordinator.oncePerSessionAndGet(taskId, supplier);
    }

    public void oncePerRun(String taskId, Runnable runnable) {
        runCoordinator.oncePerRun(taskId, runnable);
    }

    public <T> T oncePerRunAndGet(String taskId, Supplier<T> supplier) {
        return runCoordinator.oncePerRunAndGet(taskId, supplier);
    }

    /**
     * A crawl session unique identifier, which represents a
     * begin-to-end crawl, regardless how many times it may have been
     * resumed due to stops or failures. Resuming crawls will keep the
     * same session id.
     * @return crawl session id
     */
    @ToString.Include(name = "crawlSessionId")
    public String getCrawlSessionId() {
        return crawlRunInfo.getCrawlSessionId();
    }

    /**
     * A crawl run unique identifier, which is renewed every time the
     * crawler is launched, regardless whether we are resuming an existing
     * session or creating a new one.
     * @return crawl run id
     */
    @ToString.Include(name = "crawlRunId")
    public String getCrawlRunId() {
        return crawlRunInfo.getCrawlRunId();
    }

    @ToString.Include(name = "incremental")
    public boolean isIncremental() {
        return crawlRunInfo.getCrawlMode() == CrawlerMode.INCREMENTAL;
    }

    /**
     * Works out again what this run is (the crawl session it belongs to,
     * whether it resumes one, its mode and how many documents it may process)
     * after the stored history was cleared by a clean command that ran in this
     * same session.
     * <p>
     * All of that is worked out once, when the session opens, from what is
     * stored. A clean erases what it was worked out from, so a crawl that
     * follows it in the same session, as <code>start -clean</code> does, would
     * otherwise still believe it was resuming: it would skip its start
     * references and be allowed extra documents for what was cleared.
     * </p>
     */
    public void resolveRunInfoAfterClean() {
        // Cached for the run so every node adopts the same one; this one
        // describes the history that is gone.
        cluster.getCacheManager().getCrawlRunCache()
                .remove(CrawlerRunInfoResolver.CRAWL_RUN_INFO_KEY);
        crawlRunInfo = CrawlerRunInfoResolver.resolve(this);
        LOG.info("Crawl run info resolved again after clean: mode={}, "
                + "resumeState={}", crawlRunInfo.getCrawlMode(),
                crawlRunInfo.getCrawlResumeState());
        if (cluster.getLocalNode().isCoordinator()) {
            updateCrawlState(CrawlerState.RUNNING);
        }
        crawlContext.getCrawlEntryLedger().refreshMaxDocs();
    }

    @ToString.Include(name = "resumed")
    public boolean isResumed() {
        return crawlRunInfo.getCrawlResumeState() == CrawlerResumeState.RESUMED;
    }

    @ToString.Include(name = "crawlState")
    public CrawlerState getCrawlState() {
        return stateStore.getCrawlState();
    }

    @ToString.Include
    public boolean isClosed() {
        return closed;
    }

    public CrawlerAttributes getSessionAttributes() {
        if (stateStore == null) {
            throw new IllegalStateException("CrawlerSession not initialized.");
        }
        return stateStore.getSessionAttributes();
    }

    public CrawlerAttributes getCrawlRunAttributes() {
        if (runCoordinator == null) {
            throw new IllegalStateException("CrawlerSession not initialized.");
        }
        return runCoordinator.getCrawlRunAttributes();
    }

    public CrawlerAttributes getCrawlerAttributes() {
        if (crawlerAttributes == null) {
            throw new IllegalStateException("CrawlerSession not initialized.");
        }
        return crawlerAttributes;
    }

    void init() {
        if (closed) {
            throw new IllegalStateException(
                    "Cannot initialize a closed CrawlerSession.");
        }
        var clusterConfig = crawlContext.getCrawlConfig().getClusterConfig();

        LOG.info("CrawlerSession.init() - Creating directories...");
        createDir(crawlContext.getTempDir()); // also creates workDir

        LOG.info("CrawlerSession.init() - Initializing cluster...");
        // Build cache types: framework adds ledger_* -> concrete entry type;
        // driver extensions may add their own map/type mappings.
        var cacheTypes =
                new HashMap<String, Class<?>>(crawlContext.getCacheTypes());
        cacheTypes.put("ledger_*", crawlContext.getCrawlEntryType());
        cluster.init(crawlContext.getWorkDir(), clusterConfig.isClustered(),
                cacheTypes);

        LOG.info("CrawlerSession.init() - Getting cache managers...");
        var cacheManager = cluster.getCacheManager();
        var sessionCache = cacheManager.getCrawlSessionCache();
        var runCache = cacheManager.getCrawlRunCache();
        stateStore = new CrawlerStateStore(
                sessionCache, new CrawlerAttributes(sessionCache),
                new CrawlerAttributes(runCache));
        runCoordinator = new CrawlerRunCoordinator(
                sessionCache, runCache, new CrawlerAttributes(runCache));
        eventBus = new CrawlerEventBus(crawlContext.getEventManager());
        crawlerAttributes =
                new CrawlerAttributes(cacheManager.getCrawlerCache());

        var nodeName = cluster.getLocalNode().getNodeName();
        LOG.info("CrawlerSession.init() - Binding session to cluster node: {}",
                nodeName);
        cluster.bindSession(this);

        try {
            //NOTE: this resolver will also clear the session cache if needed.
            // The crawlRunCache does not need clearing as it is ephemeral
            LOG.info("CrawlerSession.init() - Resolving crawl run info...");
            crawlRunInfo = CrawlerRunInfoResolver.resolve(this);
            LOG.info("CrawlerSession.init() - Crawl run info resolved: "
                    + "mode={}, resumeState={}",
                    crawlRunInfo.getCrawlMode(),
                    crawlRunInfo.getCrawlResumeState());

            //TODO will always setting it have an impact for non crawl commands?
            if (cluster.getLocalNode().isCoordinator()) {
                LOG.info("CrawlerSession.init() - Updating crawl state to "
                        + "RUNNING (coordinator)...");
                updateCrawlState(CrawlerState.RUNNING);
                LOG.info("CrawlerSession.init() - Crawl state updated "
                        + "successfully");
            } else {
                LOG.info("CrawlerSession.init() - Skipping state update "
                        + "(not coordinator)");
            }

            LOG.info("CrawlerSession.init() - Initializing crawl context...");
            crawlContext.init(this);
            LOG.info("CrawlerSession.init() - Crawl context initialized "
                    + "successfully");

            // Start the cluster admin server
            if (!clusterConfig.isAdminDisabled()) {
                LOG.info("CrawlerSession.init() - Starting cluster "
                        + "admin server...");
                adminServer = new ClusterAdminServer(this);
                adminServer.start();
                LOG.info(
                        "CrawlerSession.init() - Cluster admin server started");
            } else {
                LOG.info("CrawlerSession.init() - Cluster admin server is "
                        + "disabled");
            }

            LOG.info("CrawlerSession.init() - Initialization complete!");
        } catch (RuntimeException e) {
            LOG.error("CrawlerSession.init() - Initialization failed!", e);
            cluster.bindSession(null);
            throw e;
        }
    }

    @Override
    public void close() {
        if (closed) {
            LOG.debug("CrawlerSession already closed.");
            return;
        }
        closed = true;

        LOG.info("Closing CrawlerSession...");

        ExceptionSwallower.runWithInterruptClear(() -> {
            ExceptionSwallower.close(crawlContext, cluster);
        });

        cluster.bindSession(null);

        if (adminServer != null) {
            adminServer.close();
        }

        LOG.info("CrawlerSession closed.");

        // Run post-close cleanup (e.g., logger stop) AFTER all
        // AutoCloseable resources have been closed and all cleanup
        // events have been fired
        if (postCloseCleanup != null) {
            ExceptionSwallower.swallowQuietly(postCloseCleanup::run);
        } else {
        }
    }

    /**
     * Registers a cleanup action to run AFTER the session has closed
     * all its resources. This is useful for components like loggers
     * that need to capture all session cleanup events before shutting down.
     * @param cleanup the cleanup action to run after session close
     */
    public void setPostCloseCleanup(Runnable cleanup) {
        postCloseCleanup = cleanup;
    }

    public int getAdminPort() {
        return adminServer.getPort();
    }

    public boolean isStartRefsQueueingComplete() {
        return stateStore.isStartRefsQueueingComplete();
    }

    public void setStartRefsQueueingComplete(boolean isComplete) {
        stateStore.setStartRefsQueueingComplete(isComplete);
    }

    public void updateCrawlState(CrawlerState state) {
        stateStore.updateCrawlState(state);
    }

    /**
     * Shortcut method for firing an event equivalent to:
     * <code>session.getCrawlContext().getEventManager().fire(event)</code>.
     * @param event the event to fire
     */
    public void fire(Event event) {
        eventBus.fire(event);
    }

    /**
     * Shortcut method for firing a {@link CrawlerEvent} with the supplied
     * source and with the <code>crawlSession</code> field already set.
     * Equivalent to:
     * <code>
     * session.getCrawlContext().getEventManager().fire(crawlerEvent)
     * </code>.
     * @param eventName name of the event to fire
     * @param source the event source
     */
    public void fire(String eventName, Object source) {
        fire(CrawlerEvent.builder()
                .name(eventName)
                .crawlSession(this)
                .source(source)
                .build());
    }

    //TODO make package private
    public State loadState() {
        return stateStore.loadState();
    }

    private void createDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new CrawlerException("Could not create directory: " + dir, e);
        }
    }

    @Data
    @Accessors(chain = true)
    //TODO make package private
    public static class State {
        private CrawlerState crawlState;
        private long lastUpdated;
    }
}
