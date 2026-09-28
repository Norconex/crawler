/* Copyright 2024-2026 Norconex Inc.
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
package com.norconex.crawler.core.context;

import static com.norconex.crawler.core.util.ExceptionSwallower.swallow;

import java.io.Closeable;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.MDC;

import com.norconex.committer.core.CommitterContext;
import com.norconex.committer.core.service.CommitterService;
import com.norconex.commons.lang.ClassUtil;
import com.norconex.commons.lang.Sleeper;
import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.bean.BeanUtil;
import com.norconex.commons.lang.event.EventManager;
import com.norconex.commons.lang.file.FileUtil;
import com.norconex.commons.lang.io.CachedStreamFactory;
import com.norconex.commons.lang.time.DurationFormatter;
import com.norconex.crawler.core.CrawlerCallbacks;
import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.cmd.crawl.pipeline.bootstrap.CrawlerBootstrapper;
import com.norconex.crawler.core.doc.pipelines.CrawlerDocPipelines;
import com.norconex.crawler.core.doc.pipelines.DedupService;
import com.norconex.crawler.core.doc.pipelines.importer.ClusterDocHandlerCache;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.fetch.Fetcher;
import com.norconex.crawler.core.ledger.CrawlerEntry;
import com.norconex.crawler.core.ledger.CrawlerEntryLedger;
import com.norconex.crawler.core.metrics.CrawlerMetrics;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.core.util.ExceptionSwallower;
import com.norconex.crawler.core.util.ScopedThreadFactoryCreator;
import com.norconex.importer.Importer;
import com.norconex.importer.doc.Doc;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.Singular;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Crawler class holding state properties required for running commands and
 * tasks.
 * </p>
 * <h2>JMX Support</h2>
 * <p>
 * JMX support is disabled by default. To enable it, set the system property
 * "enableJMX" to <code>true</code>. You can do so by adding this to your Java
 * launch command:
 * </p>
 *
 * <pre>
 *     -DenableJMX=true
 * </pre>
 */
@EqualsAndHashCode
@Getter
@Slf4j
@Builder(builderClassName = "Builder", toBuilder = true)
@NonNull
public class CrawlerContext implements Closeable {

    public static final String NAME = "CrawlerContext";

    private final DedupService dedupService;
    private final CrawlerEntryLedger crawlEntryLedger;
    private final CrawlerMetrics metrics;
    private final CrawlerConfig crawlConfig;
    private final BeanMapper beanMapper;
    private final EventManager eventManager;
    private final CrawlerCallbacks callbacks;
    private final CommitterService<Doc> committerService;
    private final Importer importer;
    @Singular
    private final List<CrawlerBootstrapper> bootstrappers;
    private final CrawlerDocPipelines docPipelines;
    private final Fetcher fetcher;
    private final Path workDir;
    private final Path tempDir;
    private final CachedStreamFactory streamFactory;
    private final Class<? extends CrawlerEntry> crawlEntryType;
    /**
     * Map of map-config name/pattern to concrete value type,
     * used to bake the correct (de)serialization class into the
     * config before startup. Populated from {@link
     * com.norconex.crawler.core.CrawlerDriver#cacheTypes()}; the framework
     * always adds {@code ledger_* -> crawlEntryType} automatically.
     */
    private final Map<String, Class<?>> cacheTypes;
    private final ScopedThreadFactoryCreator threadFactoryCreator;

    //--- Convenience methods --------------------------------------------------

    /**
     * Gets the crawler id.
     * @return crawler id
     */
    public String getId() {
        return crawlConfig.getId();
    }

    @Override
    public String toString() {
        return getId();
    }

    public CrawlerEntry createCrawlEntry(@NonNull String reference) {
        var docContext = ClassUtil.newInstance(crawlEntryType);
        docContext.setReference(reference);
        return docContext;
    }

    public CrawlerEntry createDocContext(@NonNull CrawlerEntry parentEntry) {
        var crawlEntry = createCrawlEntry(parentEntry.getReference());
        BeanUtil.copyProperties(crawlEntry, parentEntry);
        return crawlEntry;
    }

    public void init(CrawlerSession session) {
        getEventManager().addListenersFromScan(getCrawlConfig());
        getCrawlEntryLedger().init(session);
        // Publish the DOCUMENT_QUEUED application event from the component
        // that owns both session and ledger, keeping the ledger itself free
        // of any dependency on CrawlerEvent or CrawlerSession.
        getCrawlEntryLedger()
                .setQueuedListener(entry -> session.fire(CrawlerEvent.builder()
                        .name(CrawlerEvent.DOCUMENT_QUEUED)
                        .source(session)
                        .crawlEntry(entry)
                        .build()));
        //TODO consider skipping below initialization if not crawl command
        getMetrics().init(session);
        getCommitterService().init(CommitterContext
                .builder()
                .setEventManager(getEventManager())
                .setWorkDir(getWorkDir().resolve("committer"))
                .setStreamFactory(getStreamFactory())
                .build());
        //        getCrawlEntryLedger().init(session);
        getDedupService().init(session);
        getImporter().setCache(
                new ClusterDocHandlerCache(session.getCluster()
                        .getCacheManager()));
        getImporter().init();
    }

    // closes associated resources
    @Override
    public void close() {
        LOG.info("Closing CrawlerContext...");

        // Defer shutdown
        swallow(() -> Optional.ofNullable(
                getCrawlConfig().getDeferredShutdownDuration())
                .filter(d -> d.toMillis() > 0)
                .ifPresent(d -> {
                    LOG.info("Deferred shutdown requested. Pausing for {} "
                            + "starting from this UTC moment: {}",
                            DurationFormatter.FULL.format(d),
                            LocalDateTime.now(ZoneOffset.UTC));
                    Sleeper.sleepMillis(d.toMillis());
                    LOG.info("Shutdown resumed.");
                }));

        swallow(getImporter()::close);
        ExceptionSwallower.close(getCommitterService());

        swallow(MDC::clear);
        swallow(getEventManager()::clearListeners);
        ExceptionSwallower.close(getMetrics()::close);
        swallow(() -> {
            if (tempDir != null) {
                FileUtil.delete(tempDir.toFile());
            }
        }, "Could not delete the temporary directory:" + tempDir);

        LOG.info("CrawlerContext closed.");
    }
}
