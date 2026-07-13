/* Copyright 2017-2024 Norconex Inc.
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

import static java.util.Optional.ofNullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.commons.lang.map.PropertySetter;
import com.norconex.commons.lang.text.StringUtil;
import com.norconex.commons.lang.unit.DataUnit;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Truncates a <code>fromField</code> value(s) and optionally replace truncated
 * portion by a hash value to help ensure uniqueness (not 100% guaranteed to
 * be collision-free).  If the field to truncate has multiple values, all
 * values will be subject to truncation. You can store the value(s), truncated
 * or not, in another target field.
 * </p>
 * <h2>Storing values in an existing field</h2>
 * <p>
 * If a target field with the same name already exists for a document,
 * values will be added to the end of the existing value list.
 * It is possible to change this default behavior by supplying a
 * {@link PropertySetter}.
 * </p>
 * <p>
 * The <code>maxLength</code> is guaranteed to be respected. This means any
 * appended hash code and suffix will fit within the <code>maxLength</code>.
 * </p>
 * <p>
 * Can be used both as a pre-parse or post-parse handler.
 * </p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.TruncateTagger"
 *     maxLength="(maximum length)"
 *     toField="(optional target field where to store the truncated value)"
 *     {@nx.include com.norconex.commons.lang.map.PropertySetter#attributes}
 *     appendHash="[false|true]"
 *     suffix="(value to append after truncation. Goes before hash if one.)">
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <fieldMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *     (one or more matching fields to have their values truncated)
 *   </fieldMatcher>

 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="TruncateTagger"
 *     maxLength="50"
 *     appendHash="true"
 *     suffix="!">
 *   <fieldMatcher>myField</fieldMatcher>
 * </handler>
 * }
 *
 * <p>
 * Assuming this "myField" value...
 * </p>
 * <pre>    Please truncate me before you start thinking I am too long.</pre>
 * <p>
 * ...the above example will truncate it to...
 * </p>
 * <pre>    Please truncate me before you start thi!0996700004</pre>
 *
 */
@SuppressWarnings("javadoc")
@Data
@Slf4j
public class TruncateTransformer
        implements ConfigurableDocHandler<TruncateTransformerConfig> {

    private static final int BODY_CHUNK_SIZE =
            DataUnit.KB.toBytes(1).intValue();

    private final TruncateTransformerConfig configuration =
            new TruncateTransformerConfig();

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {
        if (configuration.getFieldMatcher().isSet()) {
            doFields(docCtx);
        } else {
            doBody(docCtx);
        }
        return true;
    }

    public void doBody(DocHandlerContext docCtx) throws IOException {
        var maxToRead = configuration.getMaxLength() + BODY_CHUNK_SIZE;
        var leftToWrite = configuration.getMaxLength();
        String lastChunk = null;
        String secondToLastChunk = null;
        var charRead = 0;

        try (var reader = IOUtils.buffer(docCtx.input().asReader());
                var writer = docCtx.output().asWriter()) {
            for (var text = readString(reader);
                    text != null; text = readString(reader)) {
                charRead += text.length();
                if (secondToLastChunk != null) {
                    writer.write(secondToLastChunk);
                    leftToWrite -= secondToLastChunk.length();
                }
                secondToLastChunk = lastChunk;
                lastChunk = text;
                if (charRead > maxToRead) {
                    break;
                }
            }

            var txt = ofNullable(secondToLastChunk).orElse("")
                    + ofNullable(lastChunk).orElse("");
            if (StringUtils.isBlank(txt)) {
                return;
            }

            txt = truncate(txt, leftToWrite);
            if (StringUtils.isNotBlank(configuration.getToField())) {
                // set on target field
                PropertySetter.orAppend(configuration.getOnSet()).apply(
                        docCtx.metadata(),
                        configuration.getToField(),
                        txt);
            } else {
                writer.write(txt);
            }
        }
    }

    private String readString(BufferedReader reader) throws IOException {
        var chars = new char[BODY_CHUNK_SIZE];
        var charsRead = reader.read(chars, 0, BODY_CHUNK_SIZE);
        String result;
        if (charsRead != -1) {
            result = new String(chars, 0, charsRead);
        } else {
            result = null;
        }
        return result;
    }

    public void doFields(DocHandlerContext docCtx) {
        List<String> allTargetValues = new ArrayList<>();
        for (Entry<String, List<String>> en : docCtx.metadata().matchKeys(
                configuration.getFieldMatcher()).entrySet()) {
            var fromField = en.getKey();
            var sourceValues = en.getValue();
            List<String> targetValues = new ArrayList<>();
            for (String sourceValue : sourceValues) {
                var truncValue = truncate(
                        sourceValue, configuration.getMaxLength());
                targetValues.add(truncValue);
                if (LOG.isDebugEnabled()
                        && !Objects.equals(truncValue, sourceValue)) {
                    LOG.debug(
                            "\"{}\" value truncated to \"{}\".",
                            fromField, truncValue);
                }
            }

            // toField is blank, we overwrite the source and do not
            // carry values further.
            if (StringUtils.isBlank(configuration.getToField())) {
                // overwrite source field
                PropertySetter.REPLACE.apply(
                        docCtx.metadata(), fromField, targetValues);
            } else {
                allTargetValues.addAll(targetValues);
            }
        }
        if (StringUtils.isNotBlank(configuration.getToField())) {
            // set on target field
            PropertySetter.orAppend(configuration.getOnSet()).apply(
                    docCtx.metadata(),
                    configuration.getToField(), allTargetValues);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        if (configuration.isAppendHash()) {
            return StringUtil.truncateWithHash(
                    value,
                    maxLength,
                    configuration.getSuffix());
        }
        if (StringUtils.isNotEmpty(configuration.getSuffix())) {
            return StringUtils.abbreviate(
                    value,
                    configuration.getSuffix(),
                    maxLength);
        }
        return StringUtils.truncate(value, maxLength);
    }
}
