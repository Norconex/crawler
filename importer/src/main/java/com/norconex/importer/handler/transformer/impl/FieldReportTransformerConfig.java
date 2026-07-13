/* Copyright 2019-2023 Norconex Inc.
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

import com.norconex.importer.handler.BaseDocHandlerConfig;

import java.nio.file.Path;
import java.nio.file.Paths;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>A utility tagger that reports in a CSV file the fields discovered
 * in a crawl session, captured at the point of your choice in the
 * importing process.
 * If you use this class to report on all fields discovered, make sure you
 * use it as a post-parse handler, before you are limiting which fields
 * you want to keep.
 * </p>
 * <p>
 * The report will list one field per row, along with a few sample values
 * (3 by default).  The samples will be the first ones encountered.
 * </p>
 * <p>
 * This handler does not impact the data being imported at all
 * (it only reads it). It also do not store the "content" as a field.
 * </p>
 * <p>
 * When not specified with {@link #setFile(Path)}, a file called
 * "field-report.csv" will be created in the working directory.
 * </p>
 *
 * <p>Can be used both as a pre-parse or post-parse handler.</p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.FieldReportTagger"
 *     maxSamples="(max number of sample values)"
 *     withHeaders="[false|true]"
 *     withOccurences="[false|true]"
 *     truncateSamplesAt="(number of characters to truncate long samples)"
 *     file="(path to a local file)" >
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="FieldReportTagger"
 *     maxSamples="1" file="C:\reports\field-report.csv" />
 * }
 * <p>
 * The above example logs all discovered fields into a "field-report.csv" file,
 * along with only 1 example value.
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
@Accessors(chain = true)
public class FieldReportTransformerConfig extends BaseDocHandlerConfig {

    public static final int DEFAULT_MAX_SAMPLES = 3;
    public static final Path DEFAULT_FILE = Paths.get("./field-report.csv");

    private int maxSamples = DEFAULT_MAX_SAMPLES;
    private Path file;
    private boolean withHeaders;
    private boolean withOccurences;
    private int truncateSamplesAt = -1;
}
