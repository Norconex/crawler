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
package com.norconex.crawler.core.test;

import static java.util.Optional.ofNullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.ClassUtil;
import com.norconex.commons.lang.TimeIdGenerator;
import com.norconex.commons.lang.bean.BeanMapper;
import com.norconex.commons.lang.bean.BeanMapper.Format;
import com.norconex.commons.lang.config.Configurable;
import com.norconex.crawler.core.CrawlerConfig;
import com.norconex.crawler.core.CrawlerDriver;
import com.norconex.crawler.core.Crawler;
import com.norconex.crawler.core.mocks.fetch.MockFetcher;

import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

// Instrumented to return a NodeTestResult
@Slf4j
public class TestCrawler implements Closeable {

    private record ChildProcessCleanupResult(
            Long pid,
            String outcome,
            Throwable failure) {
    }

    private static final String TEST_RESULT_FILE_NAME = "testResults.json";
    // Some clustered crawls finish but the child JVM can linger (e.g., Hazelcast
    // shutdown/repartition on Windows). For test determinism, treat the presence
    // of terminal crawl events in the periodically-written result file as
    // completion, then give the process a short grace period to exit.
    private static final long RESULT_POLL_INTERVAL_MS = 250;
    private static final int RESULT_READ_RETRY_COUNT = 4;
    private static final long RESULT_READ_RETRY_DELAY_MS = 50;
    private static final int RESULT_DELETE_RETRY_COUNT = 4;
    private static final long RESULT_DELETE_RETRY_DELAY_MS = 50;
    private static final long EXIT_GRACE_PERIOD_MS = 3_000;
    private static final String STREAM_CHILD_LOGS_PROP =
            "norconex.tests.streamChildLogs";
    private static final List<Process> ALL_CHILD_PROCESSES =
            new CopyOnWriteArrayList<>();

    static {
        // Add shutdown hook to ensure all child processes are killed when JVM exits
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutdown hook triggered. Destroying {} child processes.",
                    ALL_CHILD_PROCESSES.size());
            ALL_CHILD_PROCESSES.forEach(process -> {
                if (process.isAlive()) {
                    LOG.info("Destroying child process: {}", process);
                    process.destroyForcibly();
                }
            });
        }, "TestCrawler-ShutdownHook"));
    }

    @Getter
    private final CrawlerTestInstrument instrument;
    private final CrawlerConfig crawlConfig;
    private final EventNameRecorder eventNameRecorder = new EventNameRecorder();
    private final TestLogAppender logAppender = new TestLogAppender();
    private final CrawlerDriver crawlDriver;
    private final boolean client;
    private final AtomicReference<Process> childProcess =
            new AtomicReference<>();

    public TestCrawler(@NonNull CrawlerTestInstrument crawlTestInstrument) {
        this(crawlTestInstrument, false);
    }

    private TestCrawler(
            @NonNull CrawlerTestInstrument instrument, boolean remoteInstance) {
        Objects.requireNonNull(instrument.getWorkDir(), "workDir is required");
        var cfg = ofNullable(instrument.getCrawlConfig())
                .orElseGet(CrawlerConfig::new);
        cfg.setWorkDir(instrument.getWorkDir());
        instrument.setCrawlConfig(cfg);
        this.instrument = instrument;
        client = instrument.isNewJvm() && !remoteInstance;
        crawlDriver = ClassUtil.newInstance(
                instrument.getDriverSupplierClass()).get();

        // When running in a forked JVM the config was serialized/deserialized
        // as the base CrawlerConfig type (no @JsonTypeInfo on CrawlerConfig).
        // Re-hydrate it as the concrete driver type so driver-specific
        // callbacks (e.g. BeforeWebCommand) can safely cast the config.
        if (remoteInstance) {
            var metadataChecksummer = cfg.getMetadataChecksummer();
            var documentChecksummer = cfg.getDocumentChecksummer();
            var configClass = crawlDriver.crawlerConfigClass();
            if (configClass != null && !configClass.isInstance(cfg)) {
                try (var sw = new java.io.StringWriter()) {
                    BeanMapper.DEFAULT.write(cfg, sw, Format.YAML);
                    try (var sr = new java.io.StringReader(sw.toString())) {
                        cfg = BeanMapper.DEFAULT.read(
                                configClass, sr, Format.YAML);
                        if (metadataChecksummer == null) {
                            cfg.setMetadataChecksummer(null);
                        }
                        if (documentChecksummer == null) {
                            cfg.setDocumentChecksummer(null);
                        }
                        cfg.setWorkDir(instrument.getWorkDir());
                        instrument.setCrawlConfig(cfg);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(
                            "Failed to re-hydrate crawlConfig as "
                                    + configClass.getSimpleName(),
                            e);
                }
            }
        }

        crawlConfig = cfg;

        //NOTE: the config modifier will only exist in the local JVM instance.
        // so we apply it right away here and config changes will be serialized
        // to new JVM, so we don't have to call again.
        if (instrument.getConfigModifier() != null) {
            instrument.getConfigModifier().accept(crawlConfig);
        }
        if (instrument.getNodeConfigModifier() != null) {
            instrument.getNodeConfigModifier().accept(
                    instrument.getNodeName(), crawlConfig);
        }
    }

    public CrawlerTestNodeOutput crawl() {
        LOG.info("=== TestCrawler.crawl() called for node: {} ===",
                instrument.getNodeName());
        if (instrument.isNewJvm()) {
            LOG.info("=== Launching new JVM for node: {} ===",
                    instrument.getNodeName());
            return launchNewJvm();
        }
        LOG.info("=== Executing doCrawl() in same JVM for node: {} ===",
                instrument.getNodeName());
        return doCrawl();
    }

    public CrawlerTestNodeOutput getResult() {
        if (client) {
            return deserializeNodeTestResults();
        }
        var eventNames = crawlConfig.getEventListeners().stream()
                .filter(EventNameRecorder.class::isInstance)
                .map(EventNameRecorder.class::cast)
                .findFirst()
                .map(EventNameRecorder::getNames)
                .orElse(List.of());

        var afterCmd = crawlDriver.callbacks().getAfterCommand();
        Map<String, Map<String, String>> caches = new HashMap<>();
        if (afterCmd instanceof CachesRecorder rec) {
            caches = rec.getCaches();
        }

        return CrawlerTestNodeOutput.builder()
                .eventNames(eventNames)
                .logLines(logAppender.getMessages())
                .caches(caches)
                .build();
    }

    // For debug harness: expose workDir of child process
    Path getWorkDir() {
        return crawlConfig.getWorkDir();
    }

    @Override
    public void close() throws IOException {
        LOG.info("TestCrawler.close() called for cleanup.");
        ChildProcessCleanupResult childProcessCleanupResult = null;
        if (instrument.isRecordEvents()) {
            crawlConfig.removeEventListener(eventNameRecorder);
            LOG.info("EventNameRecorder removed.");
        }
        if (instrument.isRecordLogs()) {
            logAppender.stopCapture();
            LOG.info("LogAppender stopped.");
        }

        // Destroy child process if still running
        var process = childProcess.get();
        if (process != null) {
            childProcessCleanupResult = cleanupChildProcess(process);
            if (childProcessCleanupResult.failure() == null) {
                LOG.info(
                        "Child process cleanup summary: node='{}', pid={}, outcome='{}'",
                        instrument.getNodeName(),
                        childProcessCleanupResult.pid(),
                        childProcessCleanupResult.outcome());
            } else {
                LOG.warn(
                        "Child process cleanup summary: node='{}', pid={}, outcome='{}'",
                        instrument.getNodeName(),
                        childProcessCleanupResult.pid(),
                        childProcessCleanupResult.outcome(),
                        childProcessCleanupResult.failure());
            }
        }

        // No explicit Hazelcast or CacheManager shutdown here; handled by underlying Crawler.
        LOG.info("TestCrawler.close() cleanup complete.");
    }

    private ChildProcessCleanupResult cleanupChildProcess(Process process) {
        var pid = process.pid();
        if (!process.isAlive()) {
            ALL_CHILD_PROCESSES.remove(process);
            return new ChildProcessCleanupResult(pid, "already-exited", null);
        }

        LOG.info("Destroying child JVM process forcibly.");
        process.destroyForcibly();
        ALL_CHILD_PROCESSES.remove(process);
        try {
            if (process.waitFor(5, TimeUnit.SECONDS)) {
                return new ChildProcessCleanupResult(pid,
                        "terminated-after-destroy", null);
            }
            return new ChildProcessCleanupResult(pid,
                    "still-alive-after-timeout", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ChildProcessCleanupResult(pid,
                    "wait-interrupted", e);
        }
    }

    /**
     * Forcibly terminates the child JVM process to simulate a hard node crash.
     * No-op if this crawler is not running in a child JVM or the process has
     * already exited.
     */
    public void crash() {
        var process = childProcess.get();
        if (process != null && process.isAlive()) {
            LOG.info("Crash-killing child JVM process PID {} for node {}.",
                    process.pid(), instrument.getNodeName());
            process.destroyForcibly();
            ALL_CHILD_PROCESSES.remove(process);
        }
    }

    /**
     * Returns {@code true} if this crawler's child JVM process is currently
     * alive (i.e. launched in a separate JVM and not yet terminated).
     * @return <code>true</code> if alive
     */
    public boolean isProcessAlive() {
        var process = childProcess.get();
        return process != null && process.isAlive();
    }

    public static void main(String[] args) {
        var exitCode = 0;
        try {
            var instPath = Path.of(args[0]);
            var instrument = CoreTestUtil.readFromFile(instPath,
                    CrawlerTestInstrument.class);
            var resultPath = instrument.getCrawlConfig().getWorkDir()
                    .resolve(TEST_RESULT_FILE_NAME);
            LOG.info("TestCrawler main() starting. PID: {}",
                    ProcessHandle.current().pid());

            try (var crawler = new TestCrawler(instrument, true)) {
                CrawlerTestNodeOutput finalResult;
                if (instrument.getRecordInterval() != null) {
                    final var resultHolder =
                            new AtomicReference<CrawlerTestNodeOutput>();
                    final var crawlFailure =
                            new AtomicReference<Throwable>();
                    var crawlThread = new Thread(() -> {
                        LOG.info("crawlThread started. Thread: {}",
                                Thread.currentThread().getName());
                        try {
                            resultHolder.set(crawler.doCrawl());
                        } catch (Throwable t) {
                            crawlFailure.set(t);
                            LOG.error("crawlThread failed.", t);
                        }
                        LOG.info("crawlThread finished. Virtual Thread: {}",
                                Thread.currentThread().getName());
                    });
                    crawlThread.start();

                    var scheduler = Executors.newScheduledThreadPool(1);
                    scheduler.scheduleAtFixedRate(() -> {
                        try {
                            var result = crawler.getResult();
                            LOG.info(
                                    "Periodic result capture: {} event names recorded",
                                    result.getEventNames().size());
                            CoreTestUtil.writeToFile(result, resultPath);
                            LOG.info("Successfully wrote result to: {}",
                                    resultPath);
                        } catch (Exception e) {
                            LOG.error("Failed to write periodic result", e);
                        }
                    }, instrument.getRecordInterval().toMillis(),
                            instrument.getRecordInterval().toMillis(),
                            TimeUnit.MILLISECONDS);
                    try {
                        crawlThread.join();
                        LOG.info("crawlThread joined. Thread: {}",
                                crawlThread.getName());
                    } catch (InterruptedException e) {
                        LOG.warn("Interrupted while joining crawlThread.");
                        Thread.currentThread().interrupt();
                    }
                    scheduler.shutdown();
                    LOG.info("Scheduler shutdown initiated.");
                    try {
                        if (!scheduler.awaitTermination(5,
                                TimeUnit.SECONDS)) {
                            LOG.warn(
                                    "Scheduler did not terminate in time. Calling shutdownNow().");
                            scheduler.shutdownNow();
                        } else {
                            LOG.info("Scheduler terminated cleanly.");
                        }
                    } catch (InterruptedException e) {
                        LOG.warn(
                                "Interrupted while waiting for scheduler termination.");
                        scheduler.shutdownNow();
                        Thread.currentThread().interrupt();
                    }
                    LOG.info(
                            "After scheduler shutdown. Remaining non-daemon threads:");
                    Thread.getAllStackTraces().keySet().stream()
                            .filter(t -> t.isAlive() && !t.isDaemon())
                            .forEach(t -> LOG.info("Thread: {} (id={})",
                                    t.getName(), t.threadId()));

                    if (crawlFailure.get() != null) {
                        throw new RuntimeException(
                                "Crawler thread failed unexpectedly.",
                                crawlFailure.get());
                    }

                    finalResult = resultHolder.get();
                } else {
                    finalResult = crawler.doCrawl();
                }

                if (finalResult == null) {
                    LOG.warn("Final crawl result was null; using fallback "
                            + "snapshot from crawler state.");
                    finalResult = crawler.getResult();
                }

                CoreTestUtil.writeToFile(finalResult, resultPath);
                LOG.info("TestCrawler main() finishing. PID: {}",
                        ProcessHandle.current().pid());
                LOG.info("Active non-daemon threads at exit:");
                Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> t.isAlive() && !t.isDaemon())
                        .forEach(t -> LOG.info("Thread: {} (id={})",
                                t.getName(), t.threadId()));
            }
        } catch (IOException | RuntimeException | Error e) {
            exitCode = 1;
            LOG.error("TestCrawler main() failed", e);
        } finally {
            try {
                System.out.flush();
            } catch (Exception ignored) {
                // ignore
            }
            try {
                System.err.flush();
            } catch (Exception ignored) {
                // ignore
            }
            System.exit(exitCode);
        }
    }

    private CrawlerTestNodeOutput doCrawl() {

        instrumentCrawl();

        LOG.info("TestCrawl starting on node '{}' ...",
                instrument.getNodeName());

        CrawlerTestNodeOutput result = null;
        try {
            new Crawler(crawlDriver, crawlConfig).crawl();
        } finally {
            result = getResult();
        }
        return result;
    }

    private void instrumentCrawl() {
        // record caches
        if (instrument.isRecordCaches()) {
            var consumer = crawlDriver.callbacks().getAfterCommand();
            if (consumer instanceof CachesRecorder rec) {
                rec.setEnabled(true);
            } else {
                LOG.warn("Cache recording requested but crawl driver "
                        + "'afterCommand' callback is not 'CachesRecorder'.");
            }
        }

        // record events
        if (instrument.isRecordEvents()) {
            crawlConfig.addEventListener(eventNameRecorder);
        }

        // record logs
        if (instrument.isRecordLogs()) {
            logAppender.startCapture();
        }

        // Crawl Config modifications
        if (StringUtils.isBlank(crawlConfig.getId())) {
            if (instrument.isClustered()) {
                throw new IllegalArgumentException(
                        "Crawler 'id' must not be blank.");
            }
            crawlConfig.setId("crawl-" + TimeIdGenerator.next());
        }
        // Only apply a default fetcher if the test did not already configure
        // one (e.g., stop/resume tests use MockFetcher delay).
        if (crawlConfig.getFetchers() == null
                || crawlConfig.getFetchers().isEmpty()) {
            crawlConfig.setFetchers(
                    List.of(Configurable.configure(new MockFetcher())));
        }

        // Clustering
        crawlConfig.getClusterConfig().setClustered(instrument.isClustered());
    }

    private CrawlerTestNodeOutput launchNewJvm() {
        var nodeName = instrument.getNodeName();
        Objects.requireNonNull(nodeName, "nodeName is required");

        var workDir = instrument.getWorkDir().resolve(nodeName);
        crawlConfig.setWorkDir(workDir);

        var instrumentPath = workDir.resolve("instrument.yaml");
        CoreTestUtil.writeToFile(instrument, instrumentPath);

        // IMPORTANT: Cluster stop/resume tests can launch multiple child JVMs
        // sequentially reusing the same node workDir. Clear any previous
        // result file so terminal result detection does not pick up stale data.
        var resultPath = instrument.getCrawlConfig().getWorkDir()
                .resolve(TEST_RESULT_FILE_NAME);
        deleteStaleResultFile(nodeName, resultPath);

        final var processStartMillis = System.currentTimeMillis();
        var streamChildLogs = Boolean.getBoolean(STREAM_CHILD_LOGS_PROP);

        var launcher = JvmLauncher.builder()
                .mainClass(TestCrawler.class)
                .workDir(workDir)
                .jvmArg("-Dnorconex.tests.nodeName=" + nodeName)
                .appArg(instrumentPath.toAbsolutePath().toString());
        if (streamChildLogs) {
            launcher
                    .stdoutListener(
                            line -> LOG.info("[{}-stdout] {}", nodeName,
                                    line))
                    .stderrListener(
                            line -> LOG.error("[{}-stderr] {}", nodeName,
                                    line));
        }

        var process = launcher
                .build()
                .start();

        childProcess.set(process);
        ALL_CHILD_PROCESSES.add(process);

        LOG.info(
                "=== Node {} child process started. PID: {}. Waiting for completion... ===",
                nodeName, process.pid());
        var interrupted = false;
        var terminalResultObserved = false;
        try {
            while (process.isAlive() && !terminalResultObserved) {
                if (process.waitFor(RESULT_POLL_INTERVAL_MS,
                        java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    break;
                }
                if (Files.exists(resultPath)) {
                    try {
                        if (Files.getLastModifiedTime(resultPath)
                                .toMillis() >= processStartMillis) {
                            terminalResultObserved = isTerminalResult(
                                    deserializeNodeTestResults());
                        }
                    } catch (IOException e) {
                        // ignore transient FS issues while polling
                    }
                }
            }
        } catch (InterruptedException e) {
            interrupted = true;
            LOG.error("=== Node {} child process waitFor() INTERRUPTED ===",
                    nodeName, e);
            Thread.currentThread().interrupt();
        }

        // If we observed terminal crawl results but the process is still alive,
        // give it a short grace period to exit and then force-kill it.
        if (terminalResultObserved && process.isAlive()) {
            LOG.warn(
                    "=== Node {} produced terminal crawl results but JVM is still running; waiting {}ms then terminating ===",
                    nodeName, EXIT_GRACE_PERIOD_MS);
            try {
                if (!process.waitFor(EXIT_GRACE_PERIOD_MS,
                        java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (!process.isAlive()) {
            try {
                LOG.info(
                        "=== Node {} child process completed. Exit code: {} ===",
                        nodeName, process.exitValue());
            } catch (IllegalThreadStateException ignored) {
                // ignore
            }
        }

        // Diagnose if results file is missing. Read child STDOUT/ERR to
        // help identify why nothing was written.

        if (Files.exists(resultPath)) {
            return deserializeNodeTestResults();
        }

        // If interrupted, try one more time after a brief delay
        // (child might still be flushing results to disk)
        if (interrupted) {
            try {
                Thread.sleep(100); //NOSONAR
            } catch (InterruptedException e) {
                // Ignore
            }
            if (Files.exists(resultPath)) {
                return deserializeNodeTestResults();
            }
        }

        // If child exited with non-zero code, surface it and logs.
        try {
            var exitValue = process.exitValue();
            LOG.warn("Child JVM exited with code {} and did not produce {}",
                    exitValue, TEST_RESULT_FILE_NAME);
        } catch (IllegalThreadStateException itse) {
            LOG.warn("Child process still alive after waitFor().");
        }

        try {
            var stdout = workDir.resolve(JvmLauncher.STDOUT_FILE_NAME);
            var stderr = workDir.resolve(JvmLauncher.STDERR_FILE_NAME);
            var outText = Files.exists(stdout) ? Files.readString(stdout) : "";
            var errText = Files.exists(stderr) ? Files.readString(stderr) : "";

            var extracted =
                    CoreTestUtil.extractErrorLines(errText + "\n" + outText);
            if (!extracted.isBlank()) {
                LOG.error("Child JVM output (errors):\n{}", extracted);
            }

            LOG.debug("Child JVM full STDOUT:\n{}", outText);
            LOG.debug("Child JVM full STDERR:\n{}", errText);
        } catch (IOException e) {
            LOG.warn("Failed to read child JVM stdout/stderr", e);
        }

        return deserializeNodeTestResults();
    }

    private static boolean isTerminalResult(CrawlerTestNodeOutput output) {
        if (output == null || output.getEventNames() == null) {
            return false;
        }
        // Prefer explicit crawl end/fail events. We intentionally do not treat
        // STOPPED alone as terminal because stop/resume tests rely on a STOPPED
        // first run followed by a RESUMED second run.
        return output.getEventNames().contains("CRAWLER_CRAWL_END")
                || output.getEventNames().contains("CRAWLER_CRAWL_FAIL");
    }

    private CrawlerTestNodeOutput deserializeNodeTestResults() {
        var resultPath = instrument.getCrawlConfig().getWorkDir()
                .resolve(TEST_RESULT_FILE_NAME);
        for (var attempt = 1; attempt <= RESULT_READ_RETRY_COUNT; attempt++) {
            if (!Files.isRegularFile(resultPath)) {
                if (attempt == 1) {
                    LOG.trace("No node test results at: {}", resultPath);
                }
                return new CrawlerTestNodeOutput(List.of(), List.of(),
                        Map.of());
            }
            try {
                return CoreTestUtil.readFromFile(resultPath,
                        CrawlerTestNodeOutput.class);
            } catch (Exception e) {
                if (attempt < RESULT_READ_RETRY_COUNT
                        && isTransientResultReadFailure(e)) {
                    sleepQuietly(RESULT_READ_RETRY_DELAY_MS);
                    continue;
                }

                // File may still be empty/invalid when a node stops early,
                // but only surface that after a few retries so Windows file
                // replace races do not spam debug logs.
                LOG.debug(
                        "Could not read node test results from {} after {} attempts: {}",
                        resultPath,
                        attempt,
                        e.getMessage());
                return new CrawlerTestNodeOutput(List.of(), List.of(),
                        Map.of());
            }
        }
        return new CrawlerTestNodeOutput(List.of(), List.of(), Map.of());
    }

    private void deleteStaleResultFile(String nodeName, Path resultPath) {
        IOException lastFailure = null;
        for (var attempt = 1; attempt <= RESULT_DELETE_RETRY_COUNT; attempt++) {
            try {
                Files.deleteIfExists(resultPath);
                return;
            } catch (IOException e) {
                lastFailure = e;
                if (attempt < RESULT_DELETE_RETRY_COUNT
                        && isTransientResultReadFailure(e)) {
                    LOG.debug(
                            "Transient stale result cleanup failure for node {} at {} on attempt {}/{}; retrying in {} ms.",
                            nodeName,
                            resultPath,
                            attempt,
                            RESULT_DELETE_RETRY_COUNT,
                            RESULT_DELETE_RETRY_DELAY_MS,
                            e);
                    sleepQuietly(RESULT_DELETE_RETRY_DELAY_MS);
                    continue;
                }
                break;
            }
        }
        LOG.warn(
                "Could not delete stale {} for node {} at {} after {} attempts",
                TEST_RESULT_FILE_NAME,
                nodeName,
                resultPath,
                RESULT_DELETE_RETRY_COUNT,
                lastFailure);
    }

    private static boolean isTransientResultReadFailure(Throwable throwable) {
        var cause = throwable;
        while (cause != null) {
            if (cause instanceof AccessDeniedException
                    || cause instanceof NoSuchFileException) {
                return true;
            }
            if (cause instanceof UncheckedIOException unchecked
                    && unchecked.getCause() != null) {
                cause = unchecked.getCause();
                continue;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(delayMs); //NOSONAR
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
