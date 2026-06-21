/* Copyright 2017-2025 Norconex Inc.
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.norconex.commons.lang.collection.CollectionUtil;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.commons.lang.text.RegexFieldValueExtractor;

import lombok.Data;
import lombok.experimental.Accessors;

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
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class ExternalTransformerConfig {
    public static final String TOKEN_INPUT = "${INPUT}";
    public static final String TOKEN_OUTPUT = "${OUTPUT}";
    public static final String TOKEN_INPUT_META = "${INPUT_META}";
    public static final String TOKEN_OUTPUT_META = "${OUTPUT_META}";
    public static final String TOKEN_REFERENCE = "${REFERENCE}";

    public static final String META_FORMAT_JSON = "json";
    public static final String META_FORMAT_XML = "xml";
    public static final String META_FORMAT_PROPERTIES = "properties";

    /**
     * The command to execute. Make sure to escape spaces in
     * executable path and its arguments as well as other special command
     * line characters.
     * @param command the command
     * @return the command
     */
    private String command;

    private final List<RegexFieldValueExtractor> extractionPatterns =
            new ArrayList<>();

    /**
     * Sets the environment variables. Clearing any previously assigned
     * environment variables. When {@code null}, uses
     * the current process environment variables (default).
     * @param environmentVariables environment variables
     * @return environment variables or {@code null} if using the current
     *         process environment variables
     */
    private Map<String, String> environmentVariables = null;

    /*
     * Gets the format of the metadata input file sent to the external
     * application. One of "json" (default), "xml", or "properties" is expected.
     * Only applicable when the <code>${INPUT}</code> token
     * is part of the command.
     * @param metadataInputFormat format of the metadata input file
     * @return metadata input format
     */
    private String metadataInputFormat = META_FORMAT_JSON;

    /*
     * Sets the format of the metadata output file from the external
     * application. One of "json" (default), "xml", or "properties" is expected.
     * Set to {@code null} for relying metadata extraction
     * patterns instead.
     * Only applicable when the <code>${OUTPUT}</code> token
     * is part of the command.
     * @param metadataOutputFormat format of the metadata output file
     * @return metadata output format
     */
    private String metadataOutputFormat = META_FORMAT_JSON;

    /**
     * The directory where to store temporary files sent to the external
     * handler as file paths.
     * @param tempDir temporary directory
     * @return temporary directory
     */
    private Path tempDir;

    /**
     * Gets the property setter to use when a metadata value is set.
     * @param onSet property setter
     * @return property setter
     */
    private PropertySetter onSet;

    /**
     * Gets metadata extraction patterns. See class documentation.
     * @return extraction patterns
     */
    public List<RegexFieldValueExtractor> getExtractionPatterns() {
        return Collections.unmodifiableList(extractionPatterns);
    }

    /**
     * Sets metadata extraction patterns. See class documentation.
     * @param patterns extraction pattern
     * @return this instance
     */
    public ExternalTransformerConfig setExtractionPatterns(
            List<RegexFieldValueExtractor> extractionPatterns) {
        CollectionUtil.setAll(this.extractionPatterns, extractionPatterns);
        return this;
    }
}
