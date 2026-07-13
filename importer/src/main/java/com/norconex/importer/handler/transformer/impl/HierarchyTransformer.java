/* Copyright 2014-2024 Norconex Inc.
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>Given a separator, split a field string into multiple segments
 * representing each node of a hierarchical branch. This is useful
 * when faceting, to find out how many documents fall under each
 * node of a hierarchy. For example, take this hierarchical string:</p>
 * <pre>
 *   /vegetable/potato/sweet
 * </pre>
 * <p>We specify a slash (/) separator and it will produce the following entries
 * in the specified document metadata field:</p>
 *
 * <pre>
 *   /vegetable
 *   /vegetable/potato
 *   /vegetable/potato/sweet
 * </pre>
 * <p>
 * If no target field is specified (<code>toField</code>) the
 * source field (<code>fromField</code>) will be used to store the resulting
 * values. The same applies to the source and target hierarchy separators
 * (<code>fromSeparator</code> and <code>toSeparator</code>).
 * </p>
 * <p>
 * You can "keepEmptySegments", as well as specify
 * whether the "fromSeparator" is a regular expression. When using regular
 * expression without a "toSeparator", the text matching the expression is
 * kept as is and thus can be different for each segment.
 * </p>
 * <h2>Storing values in an existing field</h2>
 * <p>
 * If a target field with the same name already exists for a document,
 * values will be added to the end of the existing value list.
 * It is possible to change this default behavior by supplying a
 * {@link PropertySetter}.
 * </p>
 * <p>
 * Can be used both as a pre-parse or post-parse handler.
 * </p>
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.HierarchyTagger">
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <!-- multiple hierarchy tags allowed -->
 *   <hierarchy fromField="(from field)"
 *       toField="(optional to field)"
 *       fromSeparator="(original separator)"
 *       toSeparator="(optional new separator)"
 *       {@nx.include com.norconex.commons.lang.map.PropertySetter#attributes}
 *       regex="[false|true]"
 *       keepEmptySegments="[false|true]" />
 * </handler>
 * }
 *
 * {@nx.xml.example
 *  <handler class="HierarchyTagger">
 *      <hierarchy fromField="vegetable" toField="vegetableHierarchy"
 *                 fromSeparator="/"/>
 *  </handler>
 * }
 * <p>
 * The above will expand a slash-separated vegetable hierarchy found in a
 * "vegetable" field into a "vegetableHierarchy" field.
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@Data
public class HierarchyTransformer
        implements ConfigurableDocHandler<HierarchyTransformerConfig> {

    private final HierarchyTransformerConfig configuration =
            new HierarchyTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {
        configuration.getOperations().forEach(
                op -> breakSegments(docCtx.metadata(), op));
        return true;
    }

    private void breakSegments(Properties metadata, HierarchyOperation op) {

        Pattern delim;
        if (op.isRegex()) {
            delim = Pattern.compile(op.getFromSeparator());
        } else {
            delim = Pattern.compile(Pattern.quote(op.getFromSeparator()));
        }

        List<String> paths = new ArrayList<>();
        for (String value : metadata.getStrings(op.getFromField())) {
            if (value == null) {
                continue;
            }

            List<Object> segments = new ArrayList<>();
            var prevMatch = 0;
            var m = delim.matcher(value);
            while (m.find()) {
                var delimStart = m.start();
                if (prevMatch != delimStart) {
                    segments.add(value.substring(prevMatch, delimStart));
                }
                prevMatch = m.end();

                var sep = m.group();
                if (StringUtils.isNotEmpty(op.getToSeparator())) {
                    sep = op.getToSeparator();
                }
                segments.add(new Separator(sep));
            }
            if (value.length() > prevMatch) {
                segments.add(value.substring(prevMatch));
            }

            // if not keeping empty segments, keep last of a series
            // (iterating in reverse to help do so)
            var prevIsSep = false;
            if (!op.isKeepEmptySegments()) {
                var iter =
                        segments.listIterator(segments.size());
                while (iter.hasPrevious()) {
                    var seg = iter.previous();
                    if (seg instanceof Separator) {
                        if (prevIsSep) {
                            iter.remove();
                        }
                        prevIsSep = true;
                    } else {
                        prevIsSep = false;
                    }
                }
            }

            prevIsSep = false;
            var b = new StringBuilder();
            for (Object seg : segments) {
                if (seg instanceof Separator) {
                    if (prevIsSep) {
                        paths.add(b.toString());
                    }
                    b.append(seg);
                    prevIsSep = true;
                } else {
                    b.append(seg);
                    prevIsSep = false;
                    paths.add(b.toString());
                }
            }
        }

        if (StringUtils.isNotBlank(op.getToField())) {
            // set on target field
            PropertySetter.orAppend(op.getOnSet()).apply(
                    metadata, op.getToField(), paths);
        } else {
            // overwrite source field
            PropertySetter.REPLACE.apply(metadata, op.getFromField(), paths);
        }
    }

    @EqualsAndHashCode
    private static class Separator {
        private final String sep;

        public Separator(String sep) {
            this.sep = sep;
        }

        @Override
        public String toString() {
            return sep;
        }
    }
}
