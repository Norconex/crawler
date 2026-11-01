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

package com.norconex.crawler.core.test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.apache.commons.lang3.SystemUtils;

import com.norconex.crawler.core.junit.WithLogLevel;
import com.norconex.crawler.core.util.ExecUtil;

import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;
import lombok.extern.slf4j.Slf4j;

/**
 * JVM app launcher.
 */
@Builder
@Slf4j
@SuppressWarnings("null")
public class JvmLauncher {

    // to make triple-certain that the debug port is not reused on nodes
    private static final AtomicInteger launchCount = new AtomicInteger();

    public static final String STDOUT_FILE_NAME = "stdout.log";
    public static final String STDERR_FILE_NAME = "stderr.log";

    @Singular
    private final List<String> appArgs;
    @Singular
    private final List<String> jvmArgs;
    @Singular
    private final List<WithLogLevel> logLevels;
    @NonNull
    private final Class<?> mainClass;
    @NonNull
    private final Path workDir;

    /**
     * Optional: If set, lines from child JVM STDOUT will be sent to this consumer instead of being written to a file.
     */
    private final Consumer<String> stdoutListener;
    /**
     * Optional: If set, lines from child JVM STDERR will be sent to this consumer instead of being written to a file.
     */
    private final Consumer<String> stderrListener;

    public Process start() {
        List<String> command = new ArrayList<>();
        command.add(SystemUtils.JAVA_HOME + "/bin/java");
        jvmArgs.forEach(command::add);
        command.add("-Dfile.encoding=UTF8");
        command.add("-Djava.net.preferIPv4Stack=true");
        command.add("-Djava.net.preferIPv6Addresses=false");
        command.add("-Djava.net.disableIPv6=true");
        // Hazelcast Java modules
        command.addAll(List.of(
                "--add-modules", "java.se",
                "--add-exports", "java.base/jdk.internal.ref=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
                "--add-opens", "java.management/sun.management=ALL-UNNAMED",
                "--add-opens",
                "jdk.management/com.sun.management.internal=ALL-UNNAMED"));
        applyDebugMode(command);
        applyLogLevels(command);
        command.add("-cp");
        applyClasspath(command);

        command.add(mainClass.getName());
        appArgs.forEach(command::add);

        // Calculate total command length
        var fullCommand = String.join(" ", command);
        var commandLength = fullCommand.length();

        LOG.info("JVM launch command length: {} characters", commandLength);
        if (commandLength > 8191) {
            LOG.warn("""
                Command line length ({}) exceeds Windows cmd.exe \
                limit (8191). This may cause process startup \
                to fail.""", commandLength);
        }
        LOG.info("JVM launch command: {}", fullCommand);

        var pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());

        try {
            Files.createDirectories(workDir);
            boolean useStdoutListener = stdoutListener != null;
            boolean useStderrListener = stderrListener != null;
            if (!useStdoutListener) {
                pb.redirectOutput(ProcessBuilder.Redirect
                        .to(workDir.resolve(STDOUT_FILE_NAME).toFile()));
            }
            if (!useStderrListener) {
                pb.redirectError(ProcessBuilder.Redirect
                        .to(workDir.resolve(STDERR_FILE_NAME).toFile()));
            }

            var process = pb.start();
            handleProcessOutput(process, useStdoutListener, useStderrListener,
                    commandLength);
            return process;
        } catch (IOException e) {
            LOG.error("Failed to start child JVM process. Command length: {}",
                    commandLength,
                    e);
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Handles output redirection and listener threads for the launched process.
     */
    private void handleProcessOutput(Process process, boolean useStdoutListener,
            boolean useStderrListener, int commandLength) {
        if (useStdoutListener) {
            new Thread(() -> {
                try (BufferedReader reader =
                        new BufferedReader(new InputStreamReader(
                                process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdoutListener.accept(line);
                    }
                } catch (IOException e) {
                    LOG.warn("Error reading child JVM STDOUT", e);
                }
            }, "JvmLauncher-stdout-listener").start();
        }
        if (useStderrListener) {
            new Thread(() -> {
                try (BufferedReader reader =
                        new BufferedReader(new InputStreamReader(
                                process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderrListener.accept(line);
                    }
                } catch (IOException e) {
                    LOG.warn("Error reading child JVM STDERR", e);
                }
            }, "JvmLauncher-stderr-listener").start();
        }
        if (!useStdoutListener && !useStderrListener) {
            // Give the process a moment to fail if there's an immediate error
            try {
                Thread.sleep(100); //NOSONAR
                if (!process.isAlive()) {
                    var exitValue = process.exitValue();
                    LOG.error("Child JVM process exited immediately with "
                            + "code {}. Check {} and {} for details.",
                            exitValue,
                            STDOUT_FILE_NAME,
                            STDERR_FILE_NAME);

                    // Try to read error output
                    var stderrFile = workDir.resolve(STDERR_FILE_NAME);
                    if (Files.exists(stderrFile)) {
                        var stderr = Files.readString(stderrFile);
                        if (!stderr.isEmpty()) {
                            LOG.error("Child JVM stderr output:\n{}",
                                    stderr);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("Interrupted while checking process status");
            } catch (IOException e) {
                LOG.warn("Error reading child JVM stderr file", e);
            }
        }
    }

    private void applyClasspath(List<String> command) {
        var classpath = SystemUtils.JAVA_CLASS_PATH;

        // Ensure test-classes directory is included in classpath
        try {
            var testClassLocation = JvmLauncher.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()
                    .getPath();

            // Only add if not already in classpath
            if (!classpath.contains(testClassLocation)) {
                classpath = testClassLocation + File.pathSeparator + classpath;
            }
        } catch (Exception e) {
            LOG.warn("Failed to add test-classes to classpath, "
                    + "using current classpath only", e);
        }

        // On Windows, if classpath is too long, create a manifest JAR
        // to work around command line length limits
        if (SystemUtils.IS_OS_WINDOWS &&
                estimateCommandLength(classpath, command) > 8000) {
            try {
                LOG.info("Classpath too long for Windows command line, "
                        + "creating manifest JAR");
                command.add(createManifestJar(classpath).toString());
                return;
            } catch (IOException e) {
                LOG.warn("Failed to create manifest JAR, "
                        + "using original classpath",
                        e);
            }
        }
        command.add(classpath);
    }

    /**
     * Estimates the total command line length including java path,
     * JVM args, classpath, main class, and app args.
     */
    private int estimateCommandLength(String classpath, List<String> command) {
        return (SystemUtils.JAVA_HOME + "/bin/java").length()
                + command.stream().mapToInt(String::length).sum()
                //                + jvmArgs.stream().mapToInt(String::length).sum()
                //                + appArgs.stream().mapToInt(String::length).sum()
                + mainClass.getName().length()
                + classpath.length()
                + 100;
    }

    /**
     * Creates a temporary manifest-only JAR file that contains
     * the classpath in its manifest. This works around Windows
     * command line length limitations.
     */
    private Path createManifestJar(String classpath) throws IOException {
        // Use a unique manifest JAR name to avoid collisions and locked files
        var uniqueName = "classpath-manifest-" + System.nanoTime() + "-"
                + java.util.UUID.randomUUID() + ".jar";
        var manifestJar = workDir.resolve(uniqueName).toAbsolutePath();

        // Convert classpath entries to file:/// URLs for manifest
        var entries = classpath.split(File.pathSeparator);
        var manifestClasspath = new StringBuilder();

        for (var i = 0; i < entries.length; i++) {
            var entry = new File(entries[i]).toURI().toURL().toString();
            manifestClasspath.append(entry);
            if (i < entries.length - 1) {
                manifestClasspath.append(" ");
            }
        }

        // Create manifest with Class-Path attribute
        var manifest = new Manifest();
        var attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(
                Attributes.Name.CLASS_PATH,
                manifestClasspath.toString());

        // Write manifest JAR
        Files.createDirectories(workDir);
        try (var jos = new JarOutputStream(
                new FileOutputStream(manifestJar.toFile()), manifest)) {
            // No entries needed, just the manifest
        }

        // Schedule best-effort deletion on JVM exit and log the path.
        try {
            manifestJar.toFile().deleteOnExit();
        } catch (Exception e) {
            // ignore
        }

        LOG.debug("Created unique manifest JAR: {}", manifestJar);
        return manifestJar;
    }

    // Add log level system properties as JVM arguments
    private void applyLogLevels(List<String> command) {
        for (WithLogLevel logLevel : logLevels) {
            var level = logLevel.value();
            for (Class<?> clazz : logLevel.classes()) {
                command.add("-Dlog4j.logger." + clazz.getName() + "=" + level);
            }
        }
    }

    private void applyDebugMode(List<String> command) {
        if (ExecUtil.isDebugMode()) {
            command.add("-agentlib:jdwp=transport=dt_socket,"
                    + "server=y,suspend=n,address=*:"
                    + findAvailableDebugPort(
                            5005 + launchCount.getAndIncrement()));
        }
    }

    private int findAvailableDebugPort(int startPort) {
        for (var port = startPort; port < startPort + 100; port++) {
            try (var socket = new java.net.ServerSocket(port)) {
                return port;
            } catch (java.io.IOException e) {
                // Port is taken, try next
            }
        }
        throw new RuntimeException("No available debug port found");
    }
}
