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
package com.norconex.crawler.core.cmd.crawl;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.lang3.StringUtils;

import com.norconex.crawler.core.session.CrawlerSession;
import com.norconex.crawler.core.session.CrawlerState;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a machine-readable summary of a crawl when the
 * <code>runSummaryFile</code> system property names a file to write it to:
 *
 * <pre>
 *     -DrunSummaryFile=/var/run/my-crawl/summary.json
 * </pre>
 *
 * <p>
 * The property is read rather than a configuration setting because the caller
 * that wants the summary is usually the one that launched the JVM — a
 * supervising process, a scheduler, a CI job — and it needs to choose the path
 * without editing the crawl configuration. This mirrors
 * <code>-DenableJMX=true</code>, which exists for the same reason. Nothing is
 * written when the property is absent.
 * </p>
 *
 * <p>
 * The file is produced at the end of a crawl, while the ledger is still open,
 * and looks like this:
 * </p>
 *
 * <pre>
 * {
 *   "crawlerId" : "my-crawler",
 *   "state" : "COMPLETED",
 *   "counts" : { "NEW" : 3, "UNMODIFIED" : 41 }
 * }
 * </pre>
 *
 * <p>
 * Counts are keyed by
 * {@link com.norconex.crawler.core.ledger.ProcessingOutcome} name, so a reader
 * gets the crawler's own vocabulary rather than a translation of it. Obtaining
 * them costs one pass over the entries processed in this run, which is why this
 * is opt-in rather than always on.
 * </p>
 */
@Slf4j
public final class RunSummaryWriter {

    /** System property naming the file to write the run summary to. */
    public static final String SYS_PROP_RUN_SUMMARY_FILE = "runSummaryFile";

    private RunSummaryWriter() {
    }

    /**
     * Writes the summary if the system property asks for one.
     * <p>
     * A failure here is logged and swallowed. A report that could not be
     * written is not a reason to fail a crawl that otherwise succeeded, and
     * the crawl's real outcome is already recorded in the ledger and the exit
     * code.
     * </p>
     *
     * @param session    the crawl session, still open
     * @param finalState the state the crawl ended in
     */
    public static void writeIfRequested(
            CrawlerSession session, CrawlerState finalState) {
        var target = System.getProperty(SYS_PROP_RUN_SUMMARY_FILE);
        if (StringUtils.isBlank(target)) {
            return;
        }
        try {
            var path = write(Path.of(target), session, finalState);
            LOG.info("Run summary written to {}", path);
        } catch (Exception e) {
            LOG.warn("Could not write the run summary to {}: {}",
                    target, e.toString());
        }
    }

    private static Path write(
            Path target, CrawlerSession session, CrawlerState finalState)
            throws Exception {

        var summary = new RunSummary(
                session.getCrawlContext().getId(),
                finalState == null ? null : finalState.name(),
                countByOutcome(session));

        var json = JsonMapper.builder().build()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(summary);

        var parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // Written beside the target and renamed into place: whoever is waiting
        // for this file sees either no file or a complete one, never a half
        // written one.
        var tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /**
     * Tallies this run's processed entries by their processing outcome.
     * <p>
     * Sorted, so the file is stable enough to diff between runs.
     * </p>
     */
    private static Map<String, Long> countByOutcome(CrawlerSession session) {
        Map<String, Long> counts = new TreeMap<>();
        session.getCrawlContext().getCrawlEntryLedger().forEachProcessed(
                entry -> {
                    var outcome = entry.getProcessingOutcome();
                    counts.merge(
                            outcome == null ? "UNKNOWN" : outcome.toString(),
                            1L, Long::sum);
                });
        return counts;
    }

    /**
     * The summary document.
     *
     * @param crawlerId the crawler that ran
     * @param state     the state it ended in
     * @param counts    number of documents per processing outcome
     */
    public record RunSummary(
            String crawlerId,
            String state,
            Map<String, Long> counts) {
    }
}
