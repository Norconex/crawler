/* Copyright 2017-2026 Norconex Inc.
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
package com.norconex.importer.handler.transformer.impl;

import static com.norconex.importer.handler.transformer.impl.ExternalTransformerConfig.META_FORMAT_JSON;
import static com.norconex.importer.handler.transformer.impl.ExternalTransformerConfig.META_FORMAT_PROPERTIES;
import static com.norconex.importer.handler.transformer.impl.ExternalTransformerConfig.META_FORMAT_XML;
import static com.norconex.importer.handler.transformer.impl.ExternalTransformerConfig.TOKEN_INPUT;
import static com.norconex.importer.handler.transformer.impl.ExternalTransformerConfig.TOKEN_INPUT_META;
import static com.norconex.importer.handler.transformer.impl.ExternalTransformerConfig.TOKEN_OUTPUT;
import static com.norconex.importer.handler.transformer.impl.ExternalTransformerConfig.TOKEN_OUTPUT_META;
import static com.norconex.importer.handler.transformer.impl.ExternalTransformerConfig.TOKEN_REFERENCE;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.commons.lang.exec.SystemCommand;
import com.norconex.commons.lang.exec.SystemCommandException;
import com.norconex.commons.lang.io.CachedStream;
import com.norconex.commons.lang.io.InputStreamLineListener;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.commons.lang.text.RegexFieldValueExtractor;
import com.norconex.importer.ImporterRuntimeException;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/*
 * <p>
 * Class executing an external application
 * to extract data from and/or manipulate a document.
 * </p>
 *
 * <h2>Command-line arguments:</h2>
 * <p>
 * When constructing the command to launch the external application, it
 * will look for specific tokens to be replaced by file paths
 * arguments (in addition to other arguments you may have).
 * The path arguments are created by this class. They are case-sensitive and
 * the file they represent are temporary (will be deleted after
 * they have been dealt with). It is possible to omit one or more tokens to use
 * standard streams instead where applicable.
 * </p>
 * <p>
 * Tokens supported by this class are:
 * </p>
 * <dl>
 *
 *   <dt><code>${INPUT}</code></dt>
 *   <dd>Path to document to be handled by the external application.
 *       When omitted, the document content
 *       is sent to the external application using the standard input
 *       stream (STDIN).</dd>
 *
 *   <dt><code>${INPUT_META}</code></dt>
 *   <dd>Path to file containing metadata information available
 *       so far for the document to be handled by the external application.
 *       By default in JSON format. When omitted, no metadata will be made
 *       available to the external application.</dd>
 *
 *   <dt><code>${OUTPUT}</code></dt>
 *   <dd>Path to document resulting from this external handler.
 *       When omitted, the output content will be read from the external
 *       application standard output (STDOUT).</dd>
 *
 *   <dt><code>${OUTPUT_META}</code></dt>
 *   <dd>Path to file containing new metadata for the document.
 *       By default, the expected format is JSON.
 *       When omitted, any metadata extraction patterns defined will be
 *       applied against both the external program standard output (STDOUT)
 *       and standard error (STDERR). If no patterns are defined, it is
 *       assumed no new metadata resulted from the external application.</dd>
 *
 *   <dt><code>${REFERENCE}</code></dt>
 *   <dd>Unique reference to the document being handled
 *       (URL, original file system location, etc.). When omitted,
 *       the document reference will not be made available
 *       to the external application.</dd>
 *
 * </dl>
 *
 * <h2>Metadata file format:</h2>
 *
 * <p>
 * If <code>${INPUT_META}</code> is part of the command, metadata can be
 * provided to the external application in JSON (default), XML or
 * Properties format.  Those
 * formats can also be used if <code>${OUTPUT_META}</code> is part of the
 * command. The formats are:
 * </p>
 *
 * <h4>JSON</h4>
 * <pre><code class="language-json">
 * {
 *   "field1" : [ "value1a", "value1b", "value1c" ],
 *   "field2" : [ "value2" ],
 *   "field3" : [ "value3a", "value3b" ]
 * }
 * </code></pre>
 *
 * <h4>XML</h4>
 * <p>Java Properties XML file format, with the exception that
 * metadata with multiple values are supported, and will have their values
 * joined by the symbol for record separator (U+241E).
 * Example:
 * </p>
 * <pre><code class="language-xml">
 * &lt;?xml version="1.0" encoding="UTF-8"?&gt;
 * &lt;!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd"&gt;
 * &lt;properties&gt;
 *   &lt;comment&gt;My Comment&lt;/comment&gt;
 *   &lt;entry key="field1"&gt;value1a\u241Evalue1b\u241Evalue1c&lt;/entry&gt;
 *   &lt;entry key="field2"&gt;value2&lt;/entry&gt;
 *   &lt;entry key="field3"&gt;value3a\u241Evalue3b&lt;/entry&gt;
 * &lt;/properties&gt;
 * </code></pre>
 *
 * <h4>Properties</h4>
 * <p>Java Properties standard file format, with the exception that
 * metadata with multiple values are supported, and will have their values
 * joined by the symbol for record separator (U+241E). Refer to Java
 * {@link Properties#loadFromProperties(java.io.Reader)} for
 * general syntax information.
 * Example:
 * </p>
 * <pre><code class="language-properties">
 *   # My Comment
 *   field1 = value1a\u241Evalue1b\u241Evalue1c
 *   field2 = value2
 *   field3 = value3a\u241Evalue3b
 * </code></pre>
 *
 * <h2>Metadata extraction patterns:</h2>
 * <p>
 * It is possible to specify metadata extraction patterns that will be
 * applied either on the returned metadata file or from the standard output and
 * error streams.  If <code>${OUTPUT_META}</code> is found in the command,
 * the output format will be
 * used to parse the outgoing metadata file. Leave the format to
 * {@code null} to rely on extraction patterns for parsing the output file.
 * </p>
 * <p>
 * When <code>${OUTPUT_META}</code> is omitted, extraction patterns will be
 * applied to
 * the external application standard output and standard error streams. If
 * there are no <code>${OUTPUT_META}</code> and no metadata extraction patterns
 * are defined, it is assumed the external application did not produce any new
 * metadata.
 * </p>
 * <p>
 * When using metadata extraction patterns with standard streams, each pattern
 * is applied on each line returned from STDOUT and STDERR.  With each pattern,
 * there could be a matadata field name supplied. If the pattern does not
 * contain any match group, the entire matched expression will be used as the
 * metadata field value.
 * </p>
 * <p>
 * Field names and values can be obtained by using the same regular
 * expression.  This is done by using
 * match groups in your regular expressions (parenthesis).  For each pattern
 * you define, you can specify which match group hold the field name and
 * which one holds the value.
 * Specifying a field match group is optional if a <code>field</code>
 * is provided.  If no match groups are specified, a <code>field</code>
 * is expected.
 * </p>
 *
 * <h2>Storing values in an existing field</h2>
 * <p>
 * If a target field with the same name already exists for a document,
 * values will be added to the end of the existing value list.
 * It is possible to change this default behavior by supplying a
 * {@link PropertySetter}.
 * </p>
 *
 * <h2>Environment variables:</h2>
 *
 * <p>
 * Execution environment variables can be set to replace environment variables
 * defined for the current process.
 * </p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.transformer.impl.ExternalTransformer">
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <command>
 *     c:\Apps\myapp.exe ${INPUT} ${OUTPUT} ${INPUT_META} ${OUTPUT_META} ${REFERENCE}
 *   </command>
 *
 *   <metadata
 *       inputFormat="[json|xml|properties]"
 *       outputFormat="[json|xml|properties]"
 *       {@nx.include com.norconex.commons.lang.map.PropertySetter#attributes}>
 *     <!-- Pattern only used when no output format is specified.
 *          Repeat as needed. -->
 *     <pattern {@nx.include com.norconex.commons.lang.text.RegexFieldValueExtractor#attributes}>
 *       (regular expression)
 *     </pattern>
 *   </metadata>
 *
 *   <environment>
 *     <!-- repeat variable tag as needed -->
 *     <variable name="(environment variable name)">
 *       (environment variable value)
 *     </variable>
 *   </environment>
 *
 *   <tempDir>
 *     (Optional directory where to store temporary files used
 *      for transformation.)
 *   </tempDir>
 *
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="ExternalTransformer">
 *   <command>/path/transform/app ${INPUT} ${OUTPUT}</command>
 *   <metadata>
 *     <pattern field="docnumber" valueGroup="1">DocNo:(\d+)</pattern>
 *   </metadata>
 * </handler>
 * }
 * <p>
 * The above example invokes an external application that accepts two
 * files as arguments: the first one being the file to transform, the second
 * one being holding the transformation result. It also extract a document
 * number from STDOUT, found as "DocNo:1234" and storing it as "docnumber".
 * </p>
 */
@Data
@Slf4j
public class ExternalTransformer
        implements ConfigurableDocHandler<ExternalTransformerConfig> {

    private final ExternalTransformerConfig configuration =
            new ExternalTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {
        //TODO eliminate output an set it back on doc???

        var input = docCtx.input().asInputStream();
        var output = docCtx.output().asOutputStream();

        validate();
        var cmd = configuration.getCommand();
        final var files = new ArgFiles();
        var externalMeta = new Properties();

        //--- Resolve command tokens ---
        LOG.debug("Command before token replacement: {}", cmd);
        try {
            cmd = resolveInputToken(cmd, files, input);
            cmd = resolveInputMetaToken(cmd, files, input, docCtx.metadata());
            cmd = resolveOutputToken(cmd, files, output);
            cmd = resolveOutputMetaToken(cmd, files, output);
            cmd = resolveReferenceToken(cmd, docCtx.reference());

            LOG.debug("Command after token replacement: {}", cmd);

            //--- Execute Command ---
            executeCommand(cmd, files, externalMeta, input, output);
            if (files.hasOutputFile() && output != null) {
                FileUtils.copyFile(files.outputFile.toFile(), output);
                output.flush();
            }
            if (files.hasOutputMetaFile()) {
                try (Reader outputMetaReader = Files.newBufferedReader(
                        files.outputMetaFile)) {
                    var format = configuration.getMetadataOutputFormat();
                    if (META_FORMAT_PROPERTIES.equalsIgnoreCase(format)) {
                        externalMeta.loadFromProperties(outputMetaReader);
                    } else if (META_FORMAT_XML.equals(format)) {
                        externalMeta.loadFromXML(outputMetaReader);
                    } else if (META_FORMAT_JSON.equals(format)) {
                        externalMeta.loadFromJSON(outputMetaReader);
                    } else {
                        extractMetaFromFile(outputMetaReader, externalMeta);
                    }
                }
            }
            // Set extracted metadata on actual metadata
            externalMeta.forEach(
                    (k, v) -> PropertySetter
                            .orAppend(configuration.getOnSet())
                            .apply(docCtx.metadata(), k, v));
        } finally {
            files.deleteAll();
        }
        return true;
    }

    private int executeCommand(
            final String cmd,
            final ArgFiles files,
            final Properties metadata,
            final InputStream input,
            final OutputStream output) throws IOException {
        var systemCommand = new SystemCommand(cmd);
        systemCommand.setEnvironmentVariables(
                configuration.getEnvironmentVariables());
        systemCommand.addOutputListener(new InputStreamLineListener() {
            @Override
            protected void lineStreamed(String type, String line) {
                if (!files.hasOutputFile() && output != null) {
                    writeLine(line, output);
                }
                if (!files.hasOutputMetaFile()) {
                    extractMetaFromLine(line, metadata);
                }
            }
        });
        systemCommand.addErrorListener(new InputStreamLineListener() {
            @Override
            protected void lineStreamed(String type, String line) {
                if (!files.hasOutputMetaFile()) {
                    extractMetaFromLine(line, metadata);
                }
            }
        });

        try {
            int exitValue;
            if (files.hasInputFile() || input == null) {
                exitValue = systemCommand.execute();
            } else {
                exitValue = systemCommand.execute(input);
            }
            if (exitValue != 0) {
                LOG.error("Bad command exit value: {}", exitValue);
            }
            return exitValue;
        } catch (SystemCommandException e) {
            throw new IOException(
                    "External transformer failed. Command: "
                            + configuration.getCommand(),
                    e);
        }
    }

    private void writeLine(String line, OutputStream output) {
        try {
            output.write(line.getBytes());
            output.write('\n');
            output.flush();
        } catch (IOException e) {
            throw new ImporterRuntimeException(
                    "Could not write to output", e);
        }
    }

    private synchronized void extractMetaFromFile(
            Reader reader, Properties metadata) {
        Iterator<String> it = IOUtils.lineIterator(reader);
        while (it.hasNext()) {
            extractMetaFromLine(it.next(), metadata);
        }
    }

    private synchronized void extractMetaFromLine(
            String line, Properties metadata) {
        RegexFieldValueExtractor.extractFieldValues(
                metadata, line,
                configuration.getExtractionPatterns().toArray(
                        RegexFieldValueExtractor.EMPTY_ARRAY));
    }

    private Path createTempFile(
            Object stream, String name, String suffix) throws IOException {
        Path tempDirectory;
        if (configuration.getTempDir() != null) {
            tempDirectory = configuration.getTempDir();
        } else if (stream instanceof CachedStream cachedStream) {
            tempDirectory = cachedStream.getCacheDirectory();
        } else {
            tempDirectory = FileUtils.getTempDirectory().toPath();
        }
        Path file = null;
        try {
            if (!tempDirectory.toFile().exists()) {
                Files.createDirectories(tempDirectory);
            }
            return Files.createTempFile(tempDirectory, name, suffix);
        } catch (IOException e) {
            ArgFiles.delete(file);
            throw e;
        }
    }

    private String resolveInputToken(
            String cmd, ArgFiles files, InputStream is) throws IOException {
        if (!cmd.contains(TOKEN_INPUT) || is == null) {
            return cmd;
        }
        var newCmd = cmd;
        files.inputFile = createTempFile(is, "input", ".tmp");
        newCmd = Strings.CS.replace(
                newCmd, TOKEN_INPUT,
                files.inputFile.toAbsolutePath().toString());
        try {
            FileUtils.copyInputStreamToFile(is, files.inputFile.toFile());
            return newCmd;
        } catch (IOException e) {
            ArgFiles.delete(files.inputFile);
            throw e;
        }
    }

    private String resolveInputMetaToken(
            String cmd, ArgFiles files, InputStream is, Properties meta)
            throws IOException {
        if (!cmd.contains(TOKEN_INPUT_META)) {
            return cmd;
        }
        var newCmd = cmd;
        files.inputMetaFile = createTempFile(
                is, "input-meta", "." + StringUtils.defaultIfBlank(
                        configuration.getMetadataInputFormat(),
                        META_FORMAT_JSON));
        newCmd = Strings.CS.replace(
                newCmd, TOKEN_INPUT_META,
                files.inputMetaFile.toAbsolutePath().toString());
        try (Writer fw = Files.newBufferedWriter(files.inputMetaFile)) {
            var format = configuration.getMetadataInputFormat();
            if (META_FORMAT_PROPERTIES.equalsIgnoreCase(format)) {
                meta.storeToProperties(fw);
            } else if (META_FORMAT_XML.equals(format)) {
                meta.storeToXML(fw);
            } else {
                meta.storeToJSON(fw);
            }
            fw.flush();
            return newCmd;
        } catch (IOException e) {
            ArgFiles.delete(files.inputMetaFile);
            throw e;
        }
    }

    private String resolveOutputToken(
            String cmd, ArgFiles files, OutputStream os) throws IOException {
        if (!cmd.contains(TOKEN_OUTPUT) || os == null) {
            return cmd;
        }
        var newCmd = cmd;
        files.outputFile = createTempFile(os, "output", ".tmp");
        return Strings.CS.replace(
                newCmd, TOKEN_OUTPUT,
                files.outputFile.toAbsolutePath().toString());
    }

    private String resolveOutputMetaToken(
            String cmd, ArgFiles files, OutputStream os) throws IOException {
        if (!cmd.contains(TOKEN_OUTPUT_META)) {
            return cmd;
        }
        var newCmd = cmd;
        files.outputMetaFile = createTempFile(
                os, "output-meta", "." + StringUtils.defaultIfBlank(
                        configuration.getMetadataOutputFormat(), ".tmp"));
        return Strings.CS.replace(
                newCmd, TOKEN_OUTPUT_META,
                files.outputMetaFile.toAbsolutePath().toString());
    }

    private String resolveReferenceToken(String cmd, String reference) {
        if (!cmd.contains(TOKEN_REFERENCE)) {
            return cmd;
        }
        return Strings.CS.replace(cmd, TOKEN_REFERENCE, reference);
    }

    private void validate() throws IOException {
        if (StringUtils.isBlank(configuration.getCommand())) {
            throw new IOException("External command missing.");
        }
    }

    static class ArgFiles {
        Path inputFile;
        Path inputMetaFile;
        Path outputFile;
        Path outputMetaFile;

        boolean hasInputFile() {
            return inputFile != null;
        }

        boolean hasInputMetaFile() {
            return inputMetaFile != null;
        }

        boolean hasOutputFile() {
            return outputFile != null;
        }

        boolean hasOutputMetaFile() {
            return outputMetaFile != null;
        }

        void deleteAll() {
            delete(inputFile);
            delete(inputMetaFile);
            delete(outputFile);
            delete(outputMetaFile);
        }

        static void delete(Path file) {
            if (file != null) {
                try {
                    java.nio.file.Files.delete(file);
                } catch (IOException e) {
                    LOG.warn(
                            "Could not delete temporary file: "
                                    + file.toAbsolutePath(),
                            e);
                }
            }
        }
    }
}
