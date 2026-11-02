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
package com.norconex.crawler.core;

import java.nio.file.Path;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;

import com.norconex.crawler.core.cmd.Command;
import com.norconex.crawler.core.cmd.clean.CleanCommand;
import com.norconex.crawler.core.cmd.crawl.CrawlerCommand;
import com.norconex.crawler.core.cmd.stop.StopCommand;
import com.norconex.crawler.core.cmd.storeexport.StoreExportCommand;
import com.norconex.crawler.core.cmd.storeimport.StoreImportCommand;
import com.norconex.crawler.core.event.CrawlerEvent;
import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.core.session.CrawlerSessionFactory;
import com.norconex.crawler.core.util.ExceptionSwallower;
import com.norconex.crawler.core.util.LogUtil;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Crawler {

    @Getter
    private final CrawlerDriver crawlDriver;
    @Getter
    private final CrawlerConfig crawlConfig;

    public Crawler(CrawlerDriver crawlDriver, CrawlerConfig crawlConfig) {
        this.crawlDriver = crawlDriver;
        this.crawlConfig = crawlConfig;
    }

    /**
     * Crawl without first cleaning the crawler (i.e., incremental crawling).
     * Same as invoking <code>crawl(false)</code>.
     */
    public void crawl() {
        crawl(false);
    }

    /**
     *
     * @param startClean
     */
    public void crawl(boolean startClean) {
        var crawlCmd = new CrawlerCommand(crawlDriver.crawlPipelineFactory());
        if (startClean) {
            executeCommand(new CleanCommand(), crawlCmd);
        } else {
            executeCommand(crawlCmd);
        }
    }

    public void clean() {
        executeCommand(new CleanCommand());
    }

    public void stop(String... nodeUrls) {
        executeDetachedCommand(new StopCommand(crawlConfig, nodeUrls));
    }

    public void storageExport(Path dir, boolean pretty) {
        executeCommand(new StoreExportCommand(dir, pretty));
    }

    public void storageImport(Path inFile) {
        executeCommand(new StoreImportCommand(inFile));
    }

    private void validateConfig(CrawlerConfig config) {
        if (StringUtils.isBlank(config.getId())) {
            throw new CrawlerException(
                    "Crawler must be given a unique identifier (id).");
        }
    }

    // Launched without an active crawl session and not directly attached
    // to a cluster. Does not participate in normal command launch flow.
    private void executeDetachedCommand(Runnable... commands) {
        for (Runnable cmd : commands) {
            LOG.info("Executing command: {}", cmd.getClass().getSimpleName());
            ExceptionSwallower.runWithInterruptClear(cmd::run);
        }
    }

    // Launched within active crawl session.
    private void executeCommand(Command... commands) {
        validateConfig(crawlConfig);
        LogUtil.logCommandIntro(LOG, crawlConfig);

        var em = crawlDriver.eventManager();
        em.addListener(crawlDriver.callbacks());

        em.fire(CrawlerEvent.builder()
                .name(CrawlerEvent.CRAWLER_SESSION_BEGIN)
                .source(crawlConfig)
                .build());

        withCrawlSession(sess -> {
            for (int i = 0; i < commands.length; i++) {
                var cmd = commands[i];
                try {
                    LOG.info("Executing command: {}",
                            cmd.getClass().getSimpleName());
                    sess.fire(CrawlerEvent.builder()
                            .name(CrawlerEvent.CRAWLER_COMMAND_BEGIN)
                            .crawlSession(sess)
                            .commandClass(cmd.getClass())
                            .source(cmd.getClass())
                            .build());
                    ExceptionSwallower
                            .runWithInterruptClear(() -> cmd.execute(sess));
                    // What the session worked out about itself when it
                    // opened came from the history this command just erased.
                    if (cmd instanceof CleanCommand
                            && i < commands.length - 1) {
                        sess.resolveRunInfoAfterClean();
                    }
                } finally {
                    sess.fire(CrawlerEvent.builder()
                            .name(CrawlerEvent.CRAWLER_COMMAND_END)
                            .crawlSession(sess)
                            .commandClass(cmd.getClass())
                            .source(cmd.getClass())
                            .build());
                }
            }
        });

        em.fire(CrawlerEvent.builder()
                .name(CrawlerEvent.CRAWLER_SESSION_END)
                .source(crawlConfig)
                .build());
    }

    public void withCrawlSession(Consumer<CrawlerSession> c) {
        try (var sess =
                CrawlerSessionFactory.create(crawlDriver, crawlConfig)) {
            c.accept(sess);
        }
    }
}
