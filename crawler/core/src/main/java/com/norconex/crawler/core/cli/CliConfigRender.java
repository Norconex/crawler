/* Copyright 2020-2026 Norconex Inc.
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

import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.stream.Stream;

import com.norconex.commons.lang.ExceptionUtil;
import com.norconex.commons.lang.bean.BeanMapper.Format;
import com.norconex.crawler.core.Crawler;

import lombok.EqualsAndHashCode;
import lombok.ToString;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Resolve all includes and variables substitution and print the
 * resulting configuration to facilitate sharing.
 */
@Command(
    name = "configrender",
    description = "Render effective configuration"
)
@EqualsAndHashCode
@ToString
public class CliConfigRender extends CliBase {

    @Option(
        names = { "-o", "-output" },
        description = "Render to a file",
        required = false
    )
    private Path output;

    @Option(
        names = { "-format" },
        description = "One of \"xml\", \"yaml\", or \"json\" (default)",
        required = false
    )
    private String format;

    @Override
    public void runCommand(Crawler crawler) {
        //TODO support different format, either explicit, on file extension
        // or default to XML
        try (var out = output != null
                ? new FileWriter(output.toFile())
                : new StringWriter()) {

            var f = Stream
                    .of(Format.values())
                    .filter(v -> v.name().equalsIgnoreCase(format))
                    .findFirst()
                    .orElse(Format.JSON);

            crawler.getCrawlDriver()
                    .beanMapper()
                    .write(crawler.getCrawlConfig(), out, f);
            if (output == null) {
                out().println(((StringWriter) out).toString());
            }
        } catch (InvalidPathException | IOException e) {
            var msg = "Could not render config: "
                    + ExceptionUtil.getFormattedMessages(e);
            throw new CliException(msg, e);
        }
    }
}
