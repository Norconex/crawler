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
package com.norconex.crawler.core.cli;

import java.io.PrintWriter;

import com.norconex.crawler.core.CrawlerDriver;

import lombok.NonNull;
import picocli.CommandLine;
import picocli.CommandLine.PicocliException;

/**
 * Launches a crawler command with given crawler specifications and
 * command-line arguments. Meant to be invoked from a
 * <code>main(String[] args)</code> method, from
 * crawler implementations (e.g., FS, Web).
 */
public final class CliCrawlerLauncher {

    private CliCrawlerLauncher() {
    }

    public static int launch(
            @NonNull CrawlerDriver crawlDriver, String... args) {
        System.setProperty("org.jboss.logging.provider", "slf4j");
        var cmdLine = new CommandLine(new CliRunner(crawlDriver));

        // Eagerly bind picocli I/O streams to the current System.out/System.err
        // so that output is captured correctly in parallel test environments
        // where System.out/System.err may be replaced concurrently.
        cmdLine.setOut(new PrintWriter(System.out, true));
        cmdLine.setErr(new PrintWriter(System.err, true));

        cmdLine.setExecutionExceptionHandler(
                (var ex, var cli, var parseResult) -> {
                    if (ex instanceof PicocliException
                            || ex instanceof CliException) {
                        cli.getErr().println(ex.getMessage());
                        cli.getErr().println();
                        cli.usage(cli.getErr());
                        return -1;
                    }
                    throw ex;
                });

        if (args.length == 0) {
            cmdLine.getErr().println("No arguments provided.");
            cmdLine.usage(cmdLine.getOut());
            return -1;
        }

        try {
            return cmdLine.execute(args);
        } catch (Exception e) {
            e.printStackTrace(System.err);
            return -1;
        }
    }

}
