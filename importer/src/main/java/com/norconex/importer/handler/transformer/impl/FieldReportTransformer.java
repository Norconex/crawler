/* Copyright 2019-2024 Norconex Inc.
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

import static com.norconex.importer.handler.transformer.impl.FieldReportTransformerConfig.DEFAULT_FILE;
import static java.util.Optional.ofNullable;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import org.apache.commons.collections4.MapUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.commons.lang.map.Properties;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class FieldReportTransformer
        implements ConfigurableDocHandler<FieldReportTransformerConfig> {

    private final FieldReportTransformerConfig configuration =
            new FieldReportTransformerConfig();

    private final Map<String, FieldData> fields = MapUtils.lazyMap(
            new TreeMap<>(), FieldData::new);

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {
        reportFields(docCtx.metadata());
        return true;
    }

    private synchronized void reportFields(Properties metadata) {
        var dirty = false;
        for (Entry<String, List<String>> en : metadata.entrySet()) {
            if (reportField(en.getKey(), en.getValue())) {
                dirty = true;
            }
        }
        if (dirty) {
            saveReport();
        }
    }

    private boolean reportField(String field, List<String> samples) {
        var dirty = false;
        if (!fields.containsKey(field)) {
            dirty = true;
        }
        var fieldData = fields.get(field);
        if (fieldData.addSamples(
                samples,
                configuration.getMaxSamples(),
                configuration.getTruncateSamplesAt())) {
            dirty = true;
        }
        return dirty;
    }

    private void saveReport() {
        var f = ofNullable(configuration.getFile()).orElse(DEFAULT_FILE);
        try (var printer = new CSVPrinter(
                new FileWriter(f.toFile()), CSVFormat.DEFAULT)) {
            if (configuration.isWithHeaders()) {
                printer.print("Field Name");
                if (configuration.isWithOccurences()) {
                    printer.print("Occurences");
                }
                for (var i = 1; i <= configuration.getMaxSamples(); i++) {
                    printer.print("Sample Value " + i);
                }
                printer.println();
            }

            for (FieldData fieldData : fields.values()) {
                printer.print(fieldData.name);
                if (configuration.isWithOccurences()) {
                    printer.print(fieldData.occurences);
                }
                for (String value : fieldData.values) {
                    printer.print(value);
                }
                // fill the blanks
                for (var i = 0; i < configuration.getMaxSamples()
                        - fieldData.values.size(); i++) {
                    printer.print("");
                }
                printer.println();
            }
            printer.flush();
        } catch (IOException e) {
            LOG.error("Could not write field report to: " + f, e);
        }
    }

    @EqualsAndHashCode
    @ToString
    class FieldData {
        private final String name;
        private final Set<String> values = new HashSet<>();
        private int occurences;

        public FieldData(String name) {
            this.name = name;
        }

        // returns true if something changed
        public boolean addSamples(
                List<String> samples, int maxSamples, int truncateAt) {
            occurences++;
            if (samples == null) {
                return false;
            }
            var beforeCount = values.size();
            for (String sample : samples) {
                if ((values.size() >= maxSamples)
                        || !StringUtils.isNotBlank(sample)) {
                    break;
                }
                if (truncateAt > -1) {
                    values.add(StringUtils.truncate(sample, truncateAt));
                } else {
                    values.add(sample);
                }
            }
            return configuration.isWithOccurences()
                    || beforeCount != values.size();
        }
    }
}
