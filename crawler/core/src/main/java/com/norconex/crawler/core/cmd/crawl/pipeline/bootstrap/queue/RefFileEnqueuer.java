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
package com.norconex.crawler.core.cmd.crawl.pipeline.bootstrap.queue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.CrawlerException;

import lombok.extern.slf4j.Slf4j;

/**
 * Enqueues references from files obtained from the crawler configuration
 * {@link CrawlerConfig#getStartReferencesFiles()}.
 */
@Slf4j
public class RefFileEnqueuer implements ReferenceEnqueuer {

    @Override
    public int enqueue(QueueBootstrapContext ctx) {
        var cfg = ctx.getCrawlSession().getCrawlContext().getCrawlConfig();
        var refsFiles = cfg.getStartReferencesFiles();
        var cnt = 0;
        for (Path refsFile : refsFiles) {
            if (ctx.isStopRequested()) {
                break;
            }
            try (var it = IOUtils.lineIterator(Files.newInputStream(refsFile),
                    StandardCharsets.UTF_8)) {
                while (it.hasNext() && !ctx.isStopRequested()) {
                    var ref = StringUtils.trimToNull(it.next());
                    if (ref != null && !ref.startsWith("#")) {
                        ctx.queue(ref);
                        cnt++;
                    }
                }
            } catch (IOException e) {
                throw new CrawlerException(
                        "Could not process references file: " + refsFile, e);
            }
        }
        if (cnt > 0) {
            LOG.info("Queued {} start references from {} files.", cnt,
                    refsFiles.size());
        }
        return cnt;
    }
}
